-- Seed active questionnaire for symptom detection
INSERT INTO questionnaires (id, title, description, version, is_active, created_at, updated_at)
VALUES ('550e8400-e29b-41d4-a716-446655440000', 'Campus Health Survey', 'Daily health screening', 1, true, now(), now())
ON CONFLICT (id) DO NOTHING;

-- Add symptom questions
INSERT INTO questions (id, questionnaire_id, text, type, order_index)
VALUES 
('550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440000', 'Do you have a fever?', 'YES_NO', 0),
('550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440000', 'Do you have a cough?', 'YES_NO', 1)
ON CONFLICT (id) DO NOTHING;
