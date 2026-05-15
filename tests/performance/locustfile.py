import time
import uuid
from locust import HttpUser, task, between

class CircleGuardUser(HttpUser):
    wait_time = between(1, 3)
    
    def on_start(self):
        # Generamos un ID random para el usuario que está entrando
        self.anonymous_id = str(uuid.uuid4())
        self.jwt_token = None
        self.login()

    def login(self):
        # Login básico con el usuario que ya tenemos en la base
        payload = {
            "username": "staff_guard",
            "password": "password"
        }
        with self.client.post("/api/v1/auth/login", json=payload, catch_response=True) as response:
            if response.status_code == 200:
                self.jwt_token = response.json().get("jwt")
            else:
                response.failure(f"Fallo el login: {response.status_code}")

    @task(3)
    def generate_qr_and_validate(self):
        # Lo que más hace el usuario: sacar el QR y pasar por la puerta
        if not self.jwt_token:
            return
            
        headers = {"Authorization": f"Bearer {self.jwt_token}"}
        
        # 1. Sacamos el QR
        with self.client.get("/api/v1/auth/qr/generate", headers=headers, catch_response=True) as qr_resp:
            if qr_resp.status_code == 200:
                qr_token = qr_resp.json().get("qrToken")
                
                # 2. Simulamos el escaneo en la puerta (Gateway Service)
                gate_payload = {"qrToken": qr_token}
                self.client.post("/api/v1/gate/validate", json=gate_payload, name="Validar en Puerta")
            else:
                qr_resp.failure("No pudo generar el QR")

    @task(1)
    def submit_survey(self):
        # Reporte de síntomas ocasional
        if not self.jwt_token:
            return
            
        payload = {
            "anonymousId": self.anonymous_id,
            "hasFever": False,
            "hasCough": False,
            "hasHeadache": False,
            "responses": {"Q1": "No", "Q2": "No"}
        }
        headers = {"Authorization": f"Bearer {self.jwt_token}"}
        self.client.post("/api/v1/surveys", json=payload, headers=headers)

    @task(2)
    def check_health_stats(self):
        # Mirar cómo está la cosa en la universidad (dashboard)
        self.client.get("/api/v1/analytics/summary", name="Ver Dashboard")
