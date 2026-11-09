package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CharacterCardQueryRepository {
    private final EntityManager entityManager;

    public Page<CharacterCardRow> findPage(UUID workId, CharacterStatus status, String pattern, Pageable page) {
        String condition = "c.work.id = :workId and c.status = :status";
        if (!pattern.isEmpty()) {
            // 확정 발견의 entityName은 AI 인물 식별 색인과 같은 별칭 출처다.
            condition += """
                     and (lower(c.name) like lower(:pattern) escape '\\'
                       or exists (select 1 from SettingCandidate a
                           where a.work.id = :workId and a.matchedCharacterId = c.id
                             and a.candidateKind = org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind.CHARACTER_DISCOVERY
                             and a.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED
                             and lower(a.entityName) like lower(:pattern) escape '\\'))
                    """;
        }
        String order = status == CharacterStatus.ARCHIVED ? "c.updatedAt desc, c.id desc" : "c.recordCount desc, c.id desc";
        var query = entityManager.createQuery("""
                select new org.monitoring.catchholebackend.domain.character.repository.CharacterCardRow(
                    c.id, c.name, c.roleLabel, c.currentAge, c.currentLevel, e.episodeNo, c.recordCount)
                from WorkCharacter c left join Episode e on e.id = c.firstAppearanceEpisodeId and e.work.id = c.work.id
                where %s order by %s
                """.formatted(condition, order), CharacterCardRow.class);
        var count = entityManager.createQuery("select count(c) from WorkCharacter c where " + condition, Long.class);
        query.setParameter("workId", workId).setParameter("status", status);
        count.setParameter("workId", workId).setParameter("status", status);
        if (!pattern.isEmpty()) {
            query.setParameter("pattern", "%" + pattern + "%");
            count.setParameter("pattern", "%" + pattern + "%");
        }
        return new PageImpl<>(query.setFirstResult(Math.toIntExact(page.getOffset())).setMaxResults(page.getPageSize())
                .getResultList(), page, count.getSingleResult());
    }
}
