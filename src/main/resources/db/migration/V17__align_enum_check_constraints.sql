-- Align CHECK constraints with the Java enums. QuestionType.NUMBER and OperationStatus.READY/FAILED
-- exist in code but were never allowed by the schema, so writing them failed at commit.

ALTER TABLE survey_question
    DROP CONSTRAINT IF EXISTS chk_survey_question_type;

ALTER TABLE survey_question
    ADD CONSTRAINT chk_survey_question_type
        CHECK (question_type IN ('SINGLE_CHOICE', 'MULTI_CHOICE', 'OPEN_ENDED', 'RATING', 'NUMBER'));

ALTER TABLE survey_answer
    DROP CONSTRAINT IF EXISTS chk_survey_answer_type;

ALTER TABLE survey_answer
    ADD CONSTRAINT chk_survey_answer_type
        CHECK (answer_type IN ('SINGLE_CHOICE', 'MULTI_CHOICE', 'OPEN_ENDED', 'RATING', 'NUMBER'));

ALTER TABLE operation
    DROP CONSTRAINT IF EXISTS chk_operation_status;

ALTER TABLE operation
    ADD CONSTRAINT chk_operation_status
        CHECK (status IN ('DRAFT', 'READY', 'SCHEDULED', 'RUNNING', 'FAILED', 'PAUSED', 'COMPLETED', 'CANCELLED'));
