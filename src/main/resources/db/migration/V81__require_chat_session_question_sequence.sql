-- V80의 legacy 단일 질문은 보존하고, 대화에 속한 질문의 NULL 순번은 거절한다.
-- CHECK는 UNKNOWN도 허용하므로 양수 비교와 별개로 NOT NULL을 명시한다.
ALTER TABLE chat_questions DROP CONSTRAINT ck_chat_question_session;
ALTER TABLE chat_questions ADD CONSTRAINT ck_chat_question_session CHECK (
    (session_id IS NULL AND sequence_no IS NULL AND submitted_at IS NULL) OR
    (session_id IS NOT NULL AND sequence_no IS NOT NULL
        AND sequence_no > 0 AND submitted_at IS NOT NULL)
);
