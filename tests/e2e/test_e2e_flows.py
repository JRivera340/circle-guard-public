"""
CircleGuard E2E Test Suite
Framework: pytest + requests
Target: staging environment (circleguard-staging namespace)

Run:
    pip install requests pytest
    pytest tests/e2e/ -v --base-url=http://<node-ip>:<nodeport>

Environment variables:
    AUTH_URL      - auth-service base URL
    GATEWAY_URL   - gateway-service base URL
    FORM_URL      - form-service base URL
    PROMOTION_URL - promotion-service base URL
"""

import pytest
import requests
import os
import time
import uuid

# ── Configuration ────────────────────────────────────────────────────────────

AUTH_URL      = os.getenv("AUTH_URL",      "http://localhost:8180")
GATEWAY_URL   = os.getenv("GATEWAY_URL",   "http://localhost:8087")
FORM_URL      = os.getenv("FORM_URL",      "http://localhost:8086")
PROMOTION_URL = os.getenv("PROMOTION_URL", "http://localhost:8088")

# Test credentials (must exist in local DB via Flyway seed)
REGULAR_USER = {"username": "staff_guard",   "password": "password"}
ADMIN_USER   = {"username": "health_user",   "password": "password"}

from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# Configure robust session with retries for unstable local environments
def get_session():
    session = requests.Session()
    retry = Retry(
        total=5,
        backoff_factor=1,
        status_forcelist=[500, 502, 503, 504],
        allowed_methods=["HEAD", "GET", "OPTIONS", "POST"],
        raise_on_status=False
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session

session_client = get_session()

def login(credentials: dict) -> dict:
    """Login and return {jwt, anonymousId}"""
    resp = session_client.post(f"{AUTH_URL}/api/v1/auth/login", json=credentials, timeout=15)
    assert resp.status_code == 200, f"Login failed: {resp.text}"
    data = resp.json()
    return {"jwt": data["token"], "anonymousId": data.get("anonymousId", data.get("anonymous_id", ""))}


def get_qr_token(jwt: str) -> str:
    """Generate a QR token using the JWT"""
    resp = session_client.get(
        f"{AUTH_URL}/api/v1/auth/qr/generate",
        headers={"Authorization": f"Bearer {jwt}"},
        timeout=15
    )
    assert resp.status_code == 200, f"QR generation failed: {resp.text}"
    return resp.json().get("token", resp.json().get("qrToken", ""))


def validate_gate(qr_token: str, expected_status: str = None, retries: int = 5) -> dict:
    """Validate QR at the gate, returns {valid, status} with retry logic for propagation"""
    last_res = {}
    for i in range(retries):
        resp = session_client.post(
            f"{GATEWAY_URL}/api/v1/gate/validate",
            json={"token": qr_token},
            timeout=15
        )
        assert resp.status_code == 200, f"Gate validation failed: {resp.text}"
        last_res = resp.json()
        
        # If we have an expected status and we got it, we can return early
        if expected_status and last_res.get("status") == expected_status:
            return last_res
        
        # If no expected status, just return (legacy behavior)
        if not expected_status:
            return last_res
            
        # Wait a bit before next retry
        time.sleep(2)
        
    return last_res


def get_active_questionnaire() -> dict:
    resp = session_client.get(f"{FORM_URL}/api/v1/questionnaires/active", timeout=15)
    assert resp.status_code in (200, 404), f"Questionnaire fetch failed: {resp.text}"
    return resp.json() if resp.status_code == 200 else {}

# ── E2E-1: Happy Path — Healthy student enters campus ─────────────────────────

class TestHappyPathCampusEntry:
    """
    E2E-1: Flujo exitoso de entrada al campus
    Steps: Login → Generate QR → Validate at gate
    Expected: GREEN (valid=true)
    Services: auth-service, identity-service, gateway-service
    """

    def test_healthy_student_gets_green_gate(self):
        # Step 1: Login
        session = login(REGULAR_USER)
        assert session["jwt"], "JWT must not be empty"
        assert session["anonymousId"], "anonymousId must not be empty"

        # Step 2: Generate QR token
        qr_token = get_qr_token(session["jwt"])
        assert qr_token, "QR token must not be empty"

        # Step 3: Validate gate → should be GREEN
        qr_token = get_qr_token(session["jwt"])
        gate_result = validate_gate(qr_token, expected_status="GREEN")
        assert gate_result.get("status") == "GREEN", f"Expected GREEN for healthy student, got: {gate_result.get('status')}"
        assert gate_result.get("valid") is True

    def test_jwt_contains_anonymous_id(self):
        """Verify JWT carries anonymousId claim"""
        session = login(REGULAR_USER)
        assert session["anonymousId"], "JWT payload must include anonymousId"
        # anonymousId should be a UUID v4 format
        try:
            uuid.UUID(session["anonymousId"])
        except ValueError:
            pytest.fail(f"anonymousId is not a valid UUID: {session['anonymousId']}")


# ── E2E-2: Health Fencing — Sick student blocked ──────────────────────────────

class TestHealthFencingCascade:
    """
    E2E-2: Cascada de salud — estudiante con síntomas bloqueado
    Steps: Login → Submit survey with fever → Wait Kafka → Validate gate
    Expected: RED (valid=false) after status promotion to SUSPECT
    Services: auth, identity, form, promotion (Kafka), gateway
    """

    def test_symptomatic_survey_blocks_gate(self):
        # Step 1: Login as a unique test user
        session = login(REGULAR_USER)
        anon_id = session["anonymousId"]

        # Step 2: Submit health survey with fever symptom
        survey_payload = {
            "anonymousId": anon_id,
            "hasFever": True,
            "hasHeadache": True,
            "hasCough": True,
            "responses": {
                "550e8400-e29b-41d4-a716-446655440001": "YES",
                "550e8400-e29b-41d4-a716-446655440002": "YES"
            }
        }
        resp = session_client.post(
            f"{FORM_URL}/api/v1/surveys",
            json=survey_payload,
            headers={"Authorization": f"Bearer {session['jwt']}"},
            timeout=15
        )
        assert resp.status_code in (200, 201), f"Survey submission failed: {resp.text}"

        # Step 3: Wait for Kafka + promotion-service processing
        time.sleep(6)

        # Step 4: Generate new QR token
        qr_token = get_qr_token(session["jwt"])

        # Step 5: Validate at gate — should be RED now
        gate_result = validate_gate(qr_token, expected_status="RED")
        assert gate_result.get("status") == "RED", f"Expected RED after symptom, got: {gate_result}"
        assert gate_result.get("valid") is False

    def test_survey_submission_returns_200(self):
        """Verify survey endpoint accepts valid payload"""
        session = login(REGULAR_USER)
        resp = session_client.post(
            f"{FORM_URL}/api/v1/surveys",
            json={"anonymousId": session["anonymousId"], "hasFever": False},
            headers={"Authorization": f"Bearer {session['jwt']}"},
            timeout=15
        )
        assert resp.status_code in (200, 201), f"Healthy survey failed: {resp.text}"


# ── E2E-3: Admin Confirms Positive — Contacts notified ───────────────────────

class TestAdminConfirmPositive:
    """
    E2E-3: Admin marca usuario como CONFIRMED → gate RED
    Steps: Admin login → Confirm positive → Wait cascade → Gate RED
    Services: auth, promotion, notification (mock), gateway
    """

    def test_admin_confirmation_triggers_gate_red(self):
        # Step 1: Login as regular user (to get an anonymousId)
        user_session = login(REGULAR_USER)
        anon_id = user_session["anonymousId"]

        # Step 2: Login as admin (HEALTH_CENTER role)
        admin_session = login(ADMIN_USER)

        # Step 3: Admin marks user as confirmed positive
        resp = session_client.post(
            f"{PROMOTION_URL}/api/v1/health/confirmed",
            json={"anonymousId": anon_id},
            headers={"Authorization": f"Bearer {admin_session['jwt']}"},
            timeout=15
        )
        assert resp.status_code in (200, 201), f"Admin confirm failed: {resp.text}"

        # Step 4: Wait for promotion cascade (Redis update)
        time.sleep(6)

        # Step 5: Gate validation should return RED
        qr_token = get_qr_token(user_session["jwt"])
        gate_result = validate_gate(qr_token, expected_status="RED")
        assert gate_result.get("status") == "RED", \
            f"Expected RED after CONFIRMED, got: {gate_result.get('status')}"

    def test_health_stats_endpoint_returns_200(self):
        """Verify campus health stats are accessible"""
        resp = session_client.get(f"{PROMOTION_URL}/api/v1/health-status/stats", timeout=15)
        assert resp.status_code == 200, f"Stats endpoint failed: {resp.text}"


# ── E2E-4: Recovery Flow — Resolved user re-admitted ─────────────────────────

class TestRecoveryFlow:
    """
    E2E-4: Usuario en SUSPECT liberado por admin → gate GREEN
    Steps: Set SUSPECT → Verify RED → Resolve → Verify GREEN
    Services: auth, promotion, gateway
    """

    def test_resolved_user_gets_green_gate(self):
        # Step 1: Login
        user_session = login(REGULAR_USER)
        admin_session = login(ADMIN_USER)
        anon_id = user_session["anonymousId"]

        # Step 2: Set user to SUSPECT via admin confirm
        session_client.post(
            f"{PROMOTION_URL}/api/v1/health/confirmed",
            json={"anonymousId": anon_id},
            headers={"Authorization": f"Bearer {admin_session['jwt']}"},
            timeout=15
        )
        time.sleep(3)

        # Step 3: Admin resolves the user (release from fence)
        resp = session_client.post(
            f"{PROMOTION_URL}/api/v1/health/resolve",
            json={"anonymousId": anon_id, "adminOverride": True},
            headers={"Authorization": f"Bearer {admin_session['jwt']}"},
            timeout=15
        )
        assert resp.status_code in (200, 201), f"Resolve failed: {resp.text}"

        # Step 4: Wait for Redis update
        time.sleep(6)

        # Step 5: Gate should now return GREEN
        qr_token = get_qr_token(user_session["jwt"])
        gate_result = validate_gate(qr_token, expected_status="GREEN")
        assert gate_result.get("status") == "GREEN", \
            f"Expected GREEN after recovery, got: {gate_result.get('status')}"


# ── E2E-5: Questionnaire-Driven Symptom Report ────────────────────────────────

class TestQuestionnaireDrivenFlow:
    """
    E2E-5: Formulario dinámico → SymptomMapper → Kafka → gate RED
    Steps: Fetch questionnaire → Submit dynamic form → Gate RED
    Services: auth, identity, form, promotion, gateway
    """

    def test_dynamic_form_triggers_health_fence(self):
        # Step 1: Login
        session = login(REGULAR_USER)
        anon_id = session["anonymousId"]

        # Step 2: Fetch active questionnaire
        questionnaire = get_active_questionnaire()

        if not questionnaire:
            pytest.skip("No active questionnaire available in this environment")

        # Step 3: Build response with YES to fever question
        responses = {}
        for question in questionnaire.get("questions", []):
            q_id = str(question.get("id", question.get("questionId", "")))
            q_type = question.get("type", "YES_NO")
            text = question.get("text", "").lower()

            if "fever" in text or "fiebre" in text:
                responses[q_id] = "YES"
            elif q_type == "MULTI_CHOICE":
                responses[q_id] = []
            else:
                responses[q_id] = "NO"

        # Step 4: Submit survey with dynamic responses
        survey_payload = {
            "anonymousId": anon_id,
            "questionnaireId": questionnaire.get("id"),
            "responses": responses
        }
        resp = session_client.post(
            f"{FORM_URL}/api/v1/surveys",
            json=survey_payload,
            headers={"Authorization": f"Bearer {session['jwt']}"},
            timeout=15
        )
        assert resp.status_code in (200, 201), f"Dynamic survey submission failed: {resp.text}"

        # Step 5: Wait for Kafka processing
        time.sleep(8)

        # Step 6: Validate gate → should be RED
        qr_token = get_qr_token(session["jwt"])
        gate_result = validate_gate(qr_token, expected_status="RED")
        assert gate_result.get("status") == "RED", \
            f"Expected RED after questionnaire with fever, got: {gate_result.get('status')}"

    def test_active_questionnaire_has_questions(self):
        """Verify questionnaire endpoint returns questions"""
        resp = session_client.get(f"{FORM_URL}/api/v1/questionnaires/active", timeout=15)
        if resp.status_code == 404:
            pytest.skip("No active questionnaire available")
        assert resp.status_code == 200
        data = resp.json()
        assert "questions" in data or "id" in data, f"Unexpected questionnaire shape: {data}"
