package com.learnhub.assistant.memory;

import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.runtime.DeadlineJdbcTemplate;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** run 唯一领取，任何已存在状态都不重放模型；不保存原消息或模型正文。 */
@Repository
public class PreferenceExtractionAttemptStore {
    private final DeadlineJdbcTemplate jdbc;
    public PreferenceExtractionAttemptStore(DataSource dataSource) {
        jdbc = new DeadlineJdbcTemplate(dataSource);
        jdbc.setQueryTimeout(2);
    }

    public boolean claim(long owner, ChatPersistenceService.Run run, String model, String promptVersion, int input) {
        try {
            return jdbc.update("""
                    INSERT INTO assistant_preference_extraction_attempt
                    (run_id,owner_user_id,conversation_id,status,estimated_input_tokens,model_name,prompt_version)
                    SELECT r.id,r.owner_user_id,r.conversation_id,'CLAIMED',?,?,?
                    FROM assistant_run r JOIN assistant_conversation c ON c.id=r.conversation_id
                    WHERE r.id=? AND r.owner_user_id=? AND r.conversation_id=? AND r.status='RUNNING'
                    AND r.lease_owner=? AND r.lease_expires_at>CURRENT_TIMESTAMP(3)
                    AND c.owner_user_id=? AND c.deleted=0 AND c.status='ACTIVE'
                    """, input, model, promptVersion, run.id(), owner, run.conversationId(), run.leaseOwner(), owner) == 1;
        } catch (DuplicateKeyException alreadyClaimed) { return false; }
    }

    public void reserved(long owner, String runId, int tokens) {
        int changed = jdbc.update("""
                UPDATE assistant_preference_extraction_attempt SET status='RESERVED',reserved_tokens=?
                WHERE run_id=? AND owner_user_id=? AND status='CLAIMED'
                """, tokens, runId, owner);
        if (changed != 1) throw new IllegalStateException("Extraction reservation cannot be recorded");
    }

    public void usage(long owner, String runId, int prompt, int completion, int total) {
        int changed = jdbc.update("""
                UPDATE assistant_preference_extraction_attempt
                SET actual_prompt_tokens=?,actual_completion_tokens=?,actual_total_tokens=?
                WHERE run_id=? AND owner_user_id=? AND status='RESERVED'
                """, prompt, completion, total, runId, owner);
        if (changed != 1) throw new IllegalStateException("Extraction usage cannot be recorded");
    }

    public void finish(long owner, String runId, String status, String errorCode, String preferenceId) {
        if (!java.util.Set.of("ACCEPTED", "NO_MATCH", "REJECTED", "UNKNOWN", "BUDGET_REJECTED").contains(status))
            throw new IllegalArgumentException("Invalid extraction status");
        jdbc.update("""
                UPDATE assistant_preference_extraction_attempt SET status=?,error_code=?,preference_id=?
                WHERE run_id=? AND owner_user_id=? AND status IN ('CLAIMED','RESERVED')
                """, status, errorCode, preferenceId, runId, owner);
    }
}
