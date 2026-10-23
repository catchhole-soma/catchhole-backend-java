with public_jobs as (
	    select j.id, j.batch_id, j.episode_id, j.job_type, j.source_kind,
	           j.source_setting_book_id, j.status, j.analysis_mode, j.analysis_run_id,
	           j.run_generation, j.run_sequence, j.journal_status, j.review_mode,
	           j.automatic_applied_at, j.failure_code, j.checkpoint_stage, j.created_at, j.updated_at
	    from analysis_jobs j
	    where j.work_id = '47e524ae-e53c-5b8b-b430-905990a20f3d' and j.batch_id in ('551540e7-15da-551b-99d2-fa32ac754bcb','651dff26-6e30-589a-9981-6dc04c6259ba')
	      and j.job_type not in ('WORLD_SETTING_COMPARISON', 'CHARACTER_FACT_COMPARISON')
	), expanded as (
	    select j.id, j.batch_id, j.job_type, j.source_kind, j.status, j.analysis_mode,
	           j.analysis_run_id, j.run_generation, j.run_sequence, j.journal_status,
	           j.review_mode, j.automatic_applied_at, j.failure_code, j.checkpoint_stage,
	           j.created_at, j.updated_at,
	           case when j.source_kind = 'SETTING_BOOK' then j.source_setting_book_id
	                else coalesce(t.episode_id, j.episode_id) end as target_id,
	           e.episode_no
	    from public_jobs j
	    left join analysis_job_episode_targets t
	      on t.analysis_job_id = j.id and j.source_kind = 'EPISODE'
	    left join episodes e on j.source_kind = 'EPISODE'
	      and e.id = coalesce(t.episode_id, j.episode_id)
	), ranked as (
	    select e.*, row_number() over (
	        partition by batch_id, job_type, source_kind, target_id
	        order by created_at desc, id desc) as latest
	    from expanded e
	), selected as (
	    select r.* from ranked r where latest = 1
	      and (target_id is not null or not exists (
	          select 1 from ranked other
	          where other.batch_id = r.batch_id and other.job_type = r.job_type
	            and other.target_id is not null))
	), normalized as (
	    select s.*,
	           case when analysis_mode = 'ORDERED_PROVISIONAL' and journal_status = 'INVALIDATED'
	                then 'CANCELED'
	                when analysis_mode = 'ORDERED_PROVISIONAL' and status = 'SUCCEEDED'
	                  and (journal_status is null or journal_status <> 'SEALED'
	                    or (review_mode = 'AUTOMATIC' and automatic_applied_at is null))
	                then 'FAILED' else status end as listed_status,
	           case when analysis_mode = 'CONFIRMED_ONLY' and job_type = 'SETTING_EXTRACTION'
	                  and status = 'FAILED' and failure_code = 'AI_TOKEN_QUOTA_EXHAUSTED'
	                  and checkpoint_stage in ('WORLD_CANDIDATES_PUBLISHED', 'WORLD_COMPARISONS_FINISHED')
	                then 1 else 0 end as resumable_token
	    from selected s
	), ready as (
	    select n.*, case when n.analysis_mode = 'ORDERED_PROVISIONAL' and n.listed_status = 'PENDING'
	        and exists (select 1 from normalized earlier
	            where earlier.batch_id = n.batch_id and earlier.job_type = n.job_type
	              and earlier.analysis_mode = 'ORDERED_PROVISIONAL'
	              and earlier.analysis_run_id = n.analysis_run_id
	              and earlier.run_generation = n.run_generation
	              and earlier.run_sequence < n.run_sequence
	              and earlier.listed_status in ('FAILED', 'CANCELED'))
	        then 1 else 0 end as blocked
	    from normalized n
	), scopes as (select 'ALL' as scope union all select 'EPISODE' as scope), scoped as (
	    select scopes.scope, ready.* from ready cross join scopes
	    where scopes.scope = 'ALL' or ready.source_kind = 'EPISODE'
	)
	, batch_metadata as (
	    select batch_id, min(episode_no) as episode_start_no, max(episode_no) as episode_end_no,
	           count(distinct case when source_kind = 'EPISODE' then target_id end) as episode_count
	    from ready group by batch_id
	), groups as (
	    select batch_id, job_type, scope, count(*) as total,
	           sum(case when listed_status = 'PENDING' then 1 else 0 end) as pending,
	           sum(case when listed_status = 'RUNNING' then 1 else 0 end) as running,
	           sum(case when listed_status = 'SUCCEEDED' then 1 else 0 end) as succeeded,
	           sum(case when listed_status = 'FAILED' then 1 else 0 end) as failed,
	           sum(case when listed_status = 'CANCELED' then 1 else 0 end) as canceled,
	           sum(case when listed_status = 'FAILED' and resumable_token = 0 then 1 else 0 end) as terminal_failed,
	           sum(case when listed_status = 'RUNNING' or (listed_status = 'PENDING' and blocked = 0)
	                    then 1 else 0 end) as active,
	           max(updated_at) as last_activity_at
	    from scoped group by batch_id, job_type, scope
	)
	select g.batch_id, g.job_type, g.scope,
	       case when g.active > 0 then 'IN_PROGRESS' when g.canceled > 0 then 'CANCELED'
	            when g.terminal_failed > 0 and g.succeeded > 0 then 'PARTIALLY_FAILED'
	            when g.terminal_failed > 0 then 'FAILED' else 'COMPLETED' end as status,
	       g.total, g.pending, g.running, g.succeeded, g.failed, g.canceled, g.last_activity_at,
	       m.episode_start_no, m.episode_end_no, m.episode_count
	from groups g join batch_metadata m on m.batch_id = g.batch_id;
