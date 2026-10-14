package org.monitoring.catchholebackend.domain.settingtool.repository;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolQuery;
import org.monitoring.catchholebackend.domain.settingtool.processor.WorldSettingToolSources.Path;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/** 조회 종류와 정렬은 서버가 고정한다. 사용자 문자열은 모두 바인딩 변수로만 사용한다. */
@Repository
@RequiredArgsConstructor
public class SettingToolRepository {
    private final EntityManager entityManager;

    private static final String FACT_FROM = """
            from CharacterFact f join f.workCharacter c
            left join f.settingCandidate sc left join sc.episode ce left join f.sourceEpisode e
            left join sc.analysisJob aj left join f.extractedByJob fj
            where c.work.id = :workId and c.status = 'ACTIVE'
              and (sc.id is null or (sc.work.id = :workId and sc.reviewStatus = 'CONFIRMED'
                   and sc.provisionalSubjectKey is null
                   and (sc.matchedCharacterId is null or sc.matchedCharacterId = c.id)))
              and (e.id is null or e.work.id = :workId)
              and (ce.id is null or ce.work.id = :workId)
              and (e.id is null or ce.id is null or e.id = ce.id)
              and (aj.id is null or (aj.work.id = :workId and (aj.episode.id is null or aj.episode.id = coalesce(e.id, ce.id))))
              and (fj.id is null or (fj.work.id = :workId and (fj.episode.id is null or fj.episode.id = coalesce(e.id, ce.id))))
              and (aj.id is null or fj.id is null or aj.id = fj.id)
            """;
    private static final String WORLD_FROM = """
            from WorldSettingCandidate c join c.targetWorldSetting w join c.sourceEpisode e left join c.analysisJob aj
            where c.work.id = :workId and w.work.id = :workId and e.work.id = :workId
              and c.reviewStatus = 'CONFIRMED' and c.provisionalSubjectKey is null
              and (aj.id is null or (aj.work.id = :workId and (aj.episode.id is null or aj.episode.id = e.id)))
            """;
    private static final String FACT_FETCH = """
            from CharacterFact f join fetch f.workCharacter c
            left join fetch f.settingCandidate sc left join fetch sc.episode ce
            left join fetch f.sourceEpisode e left join fetch sc.analysisJob aj
            left join fetch f.extractedByJob fj
            """;
    private static final String WORLD_FETCH = """
            from WorldSettingCandidate c join fetch c.targetWorldSetting w
            join fetch c.sourceEpisode e left join fetch c.analysisJob aj
            left join fetch c.comparisonDecision
            """;

    public void assertCharacterUnchanged(WorkCharacter character) {
        var version = entityManager.createQuery("""
                select c.snapshotVersion from WorkCharacter c where c.id = :id
                and c.status = 'ACTIVE' and c.work.lifecycleStatus = 'ACTIVE'
                """, Long.class).setParameter("id", character.getId()).getResultStream().findFirst();
        if (version.isEmpty() || version.get() != character.getSnapshotVersion()) throw new AppException(CommonErrorCode.CONFLICT);
    }

    public void assertWorldUnchanged(WorldSetting world) {
        var version = entityManager.createQuery("""
                select w.version from WorldSetting w where w.id = :id and w.work.lifecycleStatus = 'ACTIVE'
                """, Long.class).setParameter("id", world.getId()).getResultStream().findFirst();
        if (version.isEmpty() || version.get() != world.getVersion()) throw new AppException(CommonErrorCode.CONFLICT);
    }

    public Page<WorkCharacter> characters(UUID workId, SettingToolQuery request) {
        var params = params(workId);
        String from = "from WorkCharacter c where c.work.id = :workId and c.status = 'ACTIVE'";
        if (request.query() != null && !request.query().isBlank()) {
            from += " and lower(c.name) like :query escape '!'";
            params.put("query", like(request.query()));
        }
        return page(WorkCharacter.class, "c", from, from, "lower(c.name), c.id", params, request);
    }

    /** Snapshot의 기존 scalar 해석을 재사용하기 위한 전체 활성 목록. 조건 적용 이전에 페이지로 자르지 않는다. */
    public List<WorkCharacter> charactersForCurrentLookup(UUID workId, String nameQuery) {
        String hql = "select c from WorkCharacter c where c.work.id = :workId and c.status = 'ACTIVE'";
        boolean named = nameQuery != null && !nameQuery.isBlank();
        if (named) hql += " and lower(c.name) like :query escape '!'";
        var query = entityManager.createQuery(hql + " order by lower(c.name), c.id", WorkCharacter.class)
                .setParameter("workId", workId);
        if (named) query.setParameter("query", like(nameQuery));
        return query.getResultList();
    }

    public List<WorldSetting> worldsForCurrentLookup(UUID workId, WorldSettingCategory category) {
        var query = entityManager.createQuery("select w from WorldSetting w where w.work.id = :workId"
                + (category == null ? "" : " and w.category = :category") + " order by w.id", WorldSetting.class)
                .setParameter("workId", workId);
        if (category != null) query.setParameter("category", category);
        return query.getResultList();
    }

    /** scalar 재조회로 1차 캐시 밖에서 추가·보관·이름 변경·snapshot 갱신을 함께 확인한다. */
    public List<Object[]> characterCollectionState(UUID workId) {
        return entityManager.createQuery("""
                select c.id, c.name, c.snapshotVersion from WorkCharacter c
                where c.work.id = :workId and c.status = 'ACTIVE' order by c.id
                """, Object[].class).setParameter("workId", workId).getResultList();
    }

    public List<Object[]> worldCollectionState(UUID workId, WorldSettingCategory category) {
        var query = entityManager.createQuery("""
                select w.id, w.subjectName, w.category, w.version from WorldSetting w
                where w.work.id = :workId
                """ + (category == null ? "" : " and w.category = :category") + " order by w.id", Object[].class)
                .setParameter("workId", workId);
        if (category != null) query.setParameter("category", category);
        return query.getResultList();
    }

    public List<Object[]> characterSchemaState(UUID workId) {
        return entityManager.createQuery("""
                select s.id, s.schemaKey, s.attributePattern, s.displayName, s.factType, s.aliasesJson
                from CharacterSettingSchema s
                where s.enabled = true and (s.work is null or s.work.id = :workId) order by s.id
                """, Object[].class).setParameter("workId", workId).getResultList();
    }

    public void assertWorkActive(UUID workId) {
        if (entityManager.createQuery("select count(w) from Work w where w.id = :id and w.lifecycleStatus = 'ACTIVE'", Long.class)
                .setParameter("id", workId).getSingleResult() != 1L) throw new AppException(CommonErrorCode.CONFLICT);
    }

    public void assertCharactersUnchanged(List<WorkCharacter> characters) {
        // 한 번에 너무 많은 IN 변수를 만들지 않고 대상별 N+1 version 조회도 피한다.
        for (int offset = 0; offset < characters.size(); offset += 500) {
            var batch = characters.subList(offset, Math.min(offset + 500, characters.size()));
            var versions = new HashMap<UUID, Long>();
            entityManager.createQuery("""
                    select c.id, c.snapshotVersion from WorkCharacter c where c.id in :ids
                    and c.status = 'ACTIVE' and c.work.lifecycleStatus = 'ACTIVE'
                    """, Object[].class).setParameter("ids", batch.stream().map(WorkCharacter::getId).toList())
                    .getResultList().forEach(row -> versions.put((UUID) row[0], (Long) row[1]));
            for (var character : batch) {
                if (!Long.valueOf(character.getSnapshotVersion()).equals(versions.get(character.getId()))) {
                    throw new AppException(CommonErrorCode.CONFLICT);
                }
            }
        }
    }

    public List<CharacterSnapshotSlot> characterHistoryFields(UUID workId, SettingToolQuery request) {
        var params = params(workId);
        params.put("targetId", request.targetId());
        String from = FACT_FROM + " and c.id = :targetId";
        if (request.factType() != null) {
            from += " and f.factType = :factType";
            params.put("factType", request.factType());
        }
        from += range("coalesce(aj.sourceEpisodeNo, fj.sourceEpisodeNo, e.episodeNo, ce.episodeNo)", request, params);
        var query = entityManager.createQuery("select distinct f.factType, f.factKey " + from, Object[].class);
        params.forEach(query::setParameter);
        return query.getResultList().stream().map(row -> new CharacterSnapshotSlot(
                (CharacterFactType) row[0], (String) row[1])).toList();
    }

    public List<Path> worldHistoryFields(UUID workId, SettingToolQuery request) {
        var params = params(workId);
        params.put("targetId", request.targetId());
        String from = WORLD_FROM + " and w.id = :targetId"
                + range("coalesce(aj.sourceEpisodeNo, e.episodeNo)", request, params);
        var query = entityManager.createQuery("select distinct c.finalScopeName, c.finalSettingName " + from, Object[].class);
        params.forEach(query::setParameter);
        return query.getResultList().stream().map(row -> new Path((String) row[0], (String) row[1])).toList();
    }

    public Page<WorldSetting> worlds(UUID workId, SettingToolQuery request) {
        var params = params(workId);
        String from = "from WorldSetting w where w.work.id = :workId";
        if (request.query() != null && !request.query().isBlank()) {
            from += " and lower(w.subjectName) like :query escape '!'";
            params.put("query", like(request.query()));
        }
        if (request.worldCategory() != null) {
            from += " and w.category = :category";
            params.put("category", request.worldCategory());
        }
        return page(WorldSetting.class, "w", from, from, "w.category, lower(w.subjectName), w.id", params, request);
    }

    public Page<CharacterFact> facts(UUID workId, SettingToolQuery request) {
        var params = params(workId);
        params.put("targetId", request.targetId());
        String from = FACT_FROM + " and c.id = :targetId";
        if (request.factType() != null) {
            from += " and f.factType = :factType";
            params.put("factType", request.factType());
        }
        if (request.settingKey() != null) {
            from += " and f.factKey = :key";
            params.put("key", request.settingKey());
        }
        from += range("coalesce(aj.sourceEpisodeNo, fj.sourceEpisodeNo, e.episodeNo, ce.episodeNo)", request, params);
        return page(CharacterFact.class, "f", from, FACT_FETCH + where(from),
                "coalesce(aj.sourceEpisodeNo, fj.sourceEpisodeNo, e.episodeNo, ce.episodeNo) asc nulls last, f.createdAt, f.id", params, request);
    }

    public Page<WorldSettingCandidate> worldHistory(UUID workId, SettingToolQuery request) {
        var params = params(workId);
        params.put("targetId", request.targetId());
        String from = WORLD_FROM + " and w.id = :targetId";
        if (request.settingKey() != null) {
            from += " and c.finalSettingName = :key";
            params.put("key", request.settingKey());
            if (request.scopeName() == null) from += " and c.finalScopeName is null";
            else {
                from += " and c.finalScopeName = :scope";
                params.put("scope", request.scopeName());
            }
        }
        from += range("coalesce(aj.sourceEpisodeNo, e.episodeNo)", request, params);
        return page(WorldSettingCandidate.class, "c", from, WORLD_FETCH + where(from),
                "coalesce(aj.sourceEpisodeNo, e.episodeNo), c.reviewedAt, c.createdAt, c.id", params, request);
    }

    /** 현재 세계관 근거 복원은 최대 501행만 읽으며, 잘린 범위는 서비스가 명시한다. */
    public List<WorldSettingCandidate> worldSources(UUID workId, UUID worldId) {
        return entityManager.createQuery("select c " + WORLD_FETCH + where(WORLD_FROM)
                        + " and w.id = :targetId order by c.reviewedAt desc, c.createdAt desc, c.id desc",
                        WorldSettingCandidate.class)
                .setParameter("workId", workId).setParameter("targetId", worldId)
                .setMaxResults(501).getResultList();
    }

    public Optional<CharacterFact> fact(UUID workId, UUID id) {
        return entityManager.createQuery("select f " + FACT_FETCH + where(FACT_FROM) + " and f.id = :id", CharacterFact.class)
                .setParameter("workId", workId).setParameter("id", id).getResultStream().findFirst();
    }

    public Optional<WorldSettingCandidate> worldEvidence(UUID workId, UUID id) {
        return entityManager.createQuery("select c " + WORLD_FETCH + where(WORLD_FROM) + " and c.id = :id", WorldSettingCandidate.class)
                .setParameter("workId", workId).setParameter("id", id).getResultStream().findFirst();
    }

    /** 선택한 slot마다 마지막 21개: 20개 참조 + 잘림 감지, legacy 표시값에는 마지막 Fact가 필요하다. */
    public List<CharacterSnapshotSource> snapshotSources(UUID characterId, List<CharacterSnapshotSlot> slots) {
        if (slots.isEmpty()) return List.of();
        var params = new HashMap<String, Object>();
        params.put("characterId", characterId);
        var predicates = new ArrayList<String>();
        for (int i = 0; i < slots.size(); i++) {
            predicates.add("(s.factType = :type" + i + " and s.factKey = :key" + i + ")");
            params.put("type" + i, slots.get(i).factType());
            params.put("key" + i, slots.get(i).factKey());
        }
        var query = entityManager.createQuery("""
                select s from CharacterSnapshotSource s join fetch s.sourceFact f
                join fetch f.workCharacter left join fetch f.settingCandidate sc
                left join fetch sc.episode left join fetch f.sourceEpisode
                left join fetch sc.analysisJob left join fetch f.extractedByJob
                where s.workCharacter.id = :characterId and (
                """ + String.join(" or ", predicates) + ")" + """
                and (select count(t) from CharacterSnapshotSource t
                     where t.workCharacter.id = s.workCharacter.id and t.factType = s.factType
                       and t.factKey = s.factKey and t.sourceOrder > s.sourceOrder) < 21
                order by s.factType, s.factKey, s.sourceOrder, s.id
                """, CharacterSnapshotSource.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    private <T> Page<T> page(Class<T> type, String alias, String countFrom, String fetchFrom,
                             String order, Map<String, Object> params, SettingToolQuery request) {
        var count = entityManager.createQuery("select count(" + alias + ") " + countFrom, Long.class);
        var query = entityManager.createQuery("select " + alias + " " + fetchFrom + " order by " + order, type);
        params.forEach((key, value) -> { count.setParameter(key, value); query.setParameter(key, value); });
        var pageable = PageRequest.of(request.page(), request.size());
        return new PageImpl<>(query.setFirstResult((int) pageable.getOffset()).setMaxResults(request.size()).getResultList(),
                pageable, count.getSingleResult());
    }

    private String range(String field, SettingToolQuery request, Map<String, Object> params) {
        String result = "";
        if (request.episodeFrom() != null) { result += " and " + field + " >= :episodeFrom"; params.put("episodeFrom", request.episodeFrom()); }
        if (request.episodeTo() != null) { result += " and " + field + " <= :episodeTo"; params.put("episodeTo", request.episodeTo()); }
        return result;
    }

    private String where(String from) { return from.substring(from.indexOf("where ")); }
    private HashMap<String, Object> params(UUID workId) {
        var result = new HashMap<String, Object>(); result.put("workId", workId); return result;
    }
    private String like(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
