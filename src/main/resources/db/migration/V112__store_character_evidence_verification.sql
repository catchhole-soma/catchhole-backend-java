-- Source addresses on historical jobs are audit metadata, not permission to read a purged source.
ALTER TABLE analysis_jobs ADD COLUMN source_reference_invalidated_at timestamp;
ALTER TABLE setting_candidates ADD COLUMN stored_evidence_validation jsonb;

CREATE FUNCTION character_evidence_input(c setting_candidates) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
 SELECT jsonb_build_array(c.work_id,c.episode_id,c.analysis_job_id,c.source_kind,
   c.source_setting_book_id,c.source_setting_book_revision_id,c.source_chunk_id,c.source_content_s3_key,
   c.evidence_spans,c.narrative_time,c.raw_ai_result_json->'setting_book_identity_proof')
$$;

-- All offsets are Unicode code points, as in Python and the existing Java contract.
CREATE FUNCTION character_evidence_span_verified(span jsonb, source_job uuid, required_chunk uuid DEFAULT NULL)
RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE a bigint; b bigint; q text; j analysis_jobs; hit boolean;
BEGIN
 IF jsonb_typeof(span) IS DISTINCT FROM 'object' OR jsonb_typeof(span->'quote') IS DISTINCT FROM 'string'
    OR btrim(span->>'quote') = '' THEN RETURN false; END IF;
 IF (span ? 'startOffset' AND span ? 'start_offset' AND span->'startOffset' <> span->'start_offset')
 OR (span ? 'endOffset' AND span ? 'end_offset' AND span->'endOffset' <> span->'end_offset') THEN RETURN false; END IF;
 IF jsonb_typeof(coalesce(span->'startOffset',span->'start_offset')) IS DISTINCT FROM 'number'
 OR jsonb_typeof(coalesce(span->'endOffset',span->'end_offset')) IS DISTINCT FROM 'number' THEN RETURN false; END IF;
 IF coalesce(span->>'startOffset',span->>'start_offset','') !~ '^[0-9]{1,10}$'
 OR coalesce(span->>'endOffset',span->>'end_offset','') !~ '^[0-9]{1,10}$' THEN RETURN false; END IF;
 a := coalesce(span->>'startOffset',span->>'start_offset')::bigint;
 b := coalesce(span->>'endOffset',span->>'end_offset')::bigint;
 q := span->>'quote';
 IF a >= b OR b > 2147483647 OR char_length(q) <> b-a THEN RETURN false; END IF;
 SELECT * INTO j FROM analysis_jobs WHERE id=source_job;
 IF j.source_kind='SETTING_BOOK' THEN
  SELECT EXISTS(SELECT 1 FROM setting_book_chunks k
   WHERE k.analysis_job_id=j.id AND k.setting_book_revision_id=j.source_setting_book_revision_id
    AND (required_chunk IS NULL OR k.id=required_chunk)
    AND a>=k.start_offset AND b<=k.end_offset
    AND k.end_offset-k.start_offset=char_length(k.chunk_text)
    AND substring(k.chunk_text FROM (a-k.start_offset+1)::int FOR (b-a)::int)=q) INTO hit;
 ELSE
  SELECT EXISTS(SELECT 1 FROM episode_chunks k JOIN episodes e ON e.id=k.episode_id
   WHERE k.episode_id=j.episode_id AND e.work_id=j.work_id AND e.status<>'ARCHIVED'
    AND e.content_s3_key=j.source_content_s3_key AND e.content_hash=j.source_content_hash
    AND e.content_s3_version IS NOT DISTINCT FROM j.source_content_s3_version
    AND (required_chunk IS NULL OR k.id=required_chunk)
    AND a>=k.start_offset AND b<=k.end_offset
    AND k.end_offset-k.start_offset=char_length(k.chunk_text)
    AND substring(k.chunk_text FROM (a-k.start_offset+1)::int FOR (b-a)::int)=q) INTO hit;
 END IF;
 RETURN coalesce(hit,false);
END $$;

CREATE FUNCTION verify_stored_character_evidence(c setting_candidates) RETURNS jsonb
LANGUAGE plpgsql STABLE AS $$
DECLARE j analysis_jobs; spans jsonb := '[]'; identities jsonb := '[]'; time_spans jsonb := '[]'; span jsonb;
 point jsonb; time_value jsonb := c.narrative_time; field text; time_valid boolean := true;
 chunk uuid; member jsonb;
BEGIN
 SELECT * INTO j FROM analysis_jobs WHERE id=c.analysis_job_id;
 IF NOT FOUND OR j.work_id<>c.work_id OR j.job_type<>'SETTING_EXTRACTION'
  OR j.source_reference_invalidated_at IS NOT NULL OR j.source_content_s3_key IS NULL
  OR j.source_content_hash IS NULL OR j.source_content_hash !~ '^[0-9a-f]{64}$'
  OR c.source_kind<>j.source_kind
  OR c.source_content_s3_key IS DISTINCT FROM j.source_content_s3_key THEN RETURN NULL; END IF;
 IF j.source_kind='EPISODE' THEN
  IF c.episode_id IS DISTINCT FROM j.episode_id OR NOT EXISTS(SELECT 1 FROM episodes e
   WHERE e.id=j.episode_id AND e.work_id=j.work_id AND e.status<>'ARCHIVED'
    AND e.content_s3_key=j.source_content_s3_key AND e.content_hash=j.source_content_hash
    AND e.content_s3_version IS NOT DISTINCT FROM j.source_content_s3_version)
   OR EXISTS(SELECT 1 FROM episode_source_purge_requests p WHERE p.episode_id=j.episode_id
    AND p.retained_content_key IS DISTINCT FROM j.source_content_s3_key) THEN RETURN NULL; END IF;
 ELSE
  IF c.source_setting_book_id IS DISTINCT FROM j.source_setting_book_id
   OR c.source_setting_book_revision_id IS DISTINCT FROM j.source_setting_book_revision_id
   OR NOT EXISTS(SELECT 1 FROM setting_book_revisions r JOIN upload_files f ON f.id=r.setting_book_id
      JOIN upload_batches b ON b.id=f.batch_id WHERE r.id=j.source_setting_book_revision_id
       AND r.setting_book_id=j.source_setting_book_id AND b.work_id=j.work_id
       AND r.content_s3_key=j.source_content_s3_key AND r.content_hash=j.source_content_hash
       AND r.content_s3_version IS NOT DISTINCT FROM j.source_content_s3_version) THEN RETURN NULL; END IF;
 END IF;
 IF jsonb_typeof(c.evidence_spans)='array' THEN
  FOR span IN SELECT value FROM jsonb_array_elements(c.evidence_spans) LOOP
   IF character_evidence_span_verified(span,j.id,c.source_chunk_id) THEN
    spans := spans || jsonb_build_array(jsonb_build_object('quote',span->>'quote',
     'startOffset',coalesce(span->'startOffset',span->'start_offset'),
     'endOffset',coalesce(span->'endOffset',span->'end_offset')));
   END IF;
  END LOOP;
 END IF;
 IF time_value IS NOT NULL AND time_value<>'null'::jsonb THEN
  IF jsonb_typeof(time_value) IS DISTINCT FROM 'object' THEN time_valid:=false;
  ELSIF NOT (time_value ?& ARRAY['factTime','referenceTime'])
   OR (SELECT count(*) FROM jsonb_object_keys(time_value))<>2 THEN time_valid:=false;
  ELSE
   FOR field IN SELECT unnest(ARRAY['factTime','referenceTime']) LOOP
    point := time_value->field;
    IF point='null'::jsonb THEN CONTINUE; END IF;
    IF jsonb_typeof(point) IS DISTINCT FROM 'object' OR jsonb_typeof(point->'description') IS DISTINCT FROM 'string'
     OR btrim(point->>'description')='' OR jsonb_typeof(point->'evidenceSpans') IS DISTINCT FROM 'array'
     THEN time_valid:=false; CONTINUE; END IF;
    IF jsonb_array_length(point->'evidenceSpans')=0 THEN time_valid:=false; CONTINUE; END IF;
    FOR span IN SELECT value FROM jsonb_array_elements(point->'evidenceSpans') LOOP
     BEGIN chunk:=(span->>'sourceChunkId')::uuid; EXCEPTION WHEN invalid_text_representation THEN chunk:=NULL; END;
     IF chunk IS NULL OR NOT character_evidence_span_verified(span,j.id,chunk) THEN time_valid:=false;
     ELSE time_spans:=time_spans||jsonb_build_array(span); END IF;
    END LOOP;
   END LOOP;
  END IF;
 END IF;
 IF jsonb_typeof(c.raw_ai_result_json#>'{setting_book_identity_proof,members}')='array' THEN
  FOR member IN SELECT value FROM jsonb_array_elements(c.raw_ai_result_json#>'{setting_book_identity_proof,members}') LOOP
   span:=member->'profileEvidence';
   IF member->'targetRef'=c.raw_ai_result_json#>'{setting_book_identity_proof,parentTargetRef}'
    AND character_evidence_span_verified(span,j.id) THEN identities:=identities||jsonb_build_array(span); END IF;
  END LOOP;
 END IF;
 RETURN jsonb_build_object('version',1,'input',character_evidence_input(c),
  'sourceHash',j.source_content_hash,'sourceKey',j.source_content_s3_key,'sourceVersion',j.source_content_s3_version,
  'evidenceSpans',spans,'narrativeTimeValid',time_valid,'narrativeSpans',time_spans,'identityEvidence',identities);
END $$;

CREATE FUNCTION capture_stored_character_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- A status-only JPA update must not discard a receipt after disposable chunks were rebuilt.
 IF TG_OP='UPDATE' AND character_evidence_input(NEW)=character_evidence_input(OLD)
  AND OLD.stored_evidence_validation IS NOT NULL THEN
  NEW.stored_evidence_validation:=OLD.stored_evidence_validation;
 ELSE NEW.stored_evidence_validation:=verify_stored_character_evidence(NEW); END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION invalidate_purged_analysis_source_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE analysis_jobs SET source_reference_invalidated_at=coalesce(source_reference_invalidated_at,NEW.requested_at)
 WHERE work_id=NEW.work_id AND source_kind='EPISODE' AND episode_id=NEW.episode_id
  AND source_content_s3_key IS DISTINCT FROM NEW.retained_content_key;
 RETURN NEW;
END $$;
CREATE TRIGGER invalidate_purged_analysis_source_reference
 AFTER INSERT ON episode_source_purge_requests FOR EACH ROW EXECUTE FUNCTION invalidate_purged_analysis_source_reference();

-- Existing deleted/replaced sources are unavailable even though the purge queue has already drained.
UPDATE analysis_jobs j SET source_reference_invalidated_at=coalesce(e.updated_at,localtimestamp)
FROM episodes e WHERE j.episode_id=e.id AND j.source_kind='EPISODE'
 AND j.source_content_s3_key IS NOT NULL AND (e.status='ARCHIVED'
  OR e.content_s3_key IS DISTINCT FROM j.source_content_s3_key
  OR EXISTS(SELECT 1 FROM episode_source_purge_requests p WHERE p.episode_id=e.id
   AND p.retained_content_key IS DISTINCT FROM j.source_content_s3_key));
-- Only still-bound, verifiable chunks can certify old quotes. Missing old chunks never cause an S3 fallback.
UPDATE setting_candidates SET stored_evidence_validation=verify_stored_character_evidence(setting_candidates)
 WHERE analysis_job_id IS NOT NULL;

CREATE TRIGGER capture_stored_character_evidence
 BEFORE INSERT OR UPDATE OF work_id,episode_id,analysis_job_id,source_kind,source_setting_book_id,
 source_setting_book_revision_id,source_chunk_id,source_content_s3_key,evidence_spans,narrative_time,raw_ai_result_json,stored_evidence_validation
 ON setting_candidates FOR EACH ROW EXECUTE FUNCTION capture_stored_character_evidence();
