package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldImageBackfillRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageBackfillResponse;
import org.monitoring.catchholebackend.domain.worldimage.processor.*;
import org.monitoring.catchholebackend.domain.worldimage.repository.WorldImageCatalogRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorldImageBackfillServiceImpl implements WorldImageBackfillService {
    private final WorkRepository works;
    private final WorkCharacterRepository characters;
    private final WorldSettingRepository settings;
    private final WorldImageCatalogRepository catalogs;
    private final CharacterRaceImageMatcher characterMatcher;
    private final WorldSettingImageMatcher settingMatcher;
    private final AutomaticImageService automaticImages;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public WorldImageBackfillResponse backfillImages(WorldImageBackfillRequest request) {
        // 선택 변경·확정·작품 purge와 같은 Work 잠금으로 직렬화한다. 한 요청은 최대 500개다.
        var work = works.findByIdForUpdate(request.workId()).orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
        work.requireActive();
        int limit = request.limit() == null ? 100 : request.limit();
        boolean apply = Boolean.TRUE.equals(request.apply());
        boolean character = request.kind() == WorldImageBackfillRequest.Kind.CHARACTER;
        String sql = character
                ? "select c.id from characters c left join character_images i on i.character_id=c.id where c.work_id=? and i.selection_source is null order by c.id limit ?"
                : "select s.id from world_settings s left join world_setting_images i on i.world_setting_id=s.id where s.work_id=? and i.world_setting_id is null order by s.id limit ?";
        List<UUID> ids = jdbc.query(sql, (rs, n) -> rs.getObject(1, UUID.class), work.getId(), limit);
        if (ids.isEmpty()) return new WorldImageBackfillResponse(0, 0, 0, apply);
        int matched;
        var session = entityManager.unwrap(Session.class);
        Integer previousBatchSize = session.getJdbcBatchSize();
        try {
            // 보정 작업의 insert/update만 묶는다. 일반 애플리케이션 세션 설정은 바꾸지 않는다.
            session.setJdbcBatchSize(50);
            if (character) {
                var subjects = characters.findAllById(ids);
                var races = catalogs.findRaceImagesWithAliases();
                matched = (int) subjects.stream().filter(c -> characterMatcher.matchRace(c, races) != null).count();
                if (apply) automaticImages.refreshCharacterImages(subjects);
            } else {
                var subjects = settings.findAllById(ids);
                var images = catalogs.findAutomaticWorldImagesWithAliases(WorldImageThemes.forGenre(work.getGenre()));
                matched = (int) subjects.stream().filter(s -> settingMatcher.match(s, images) != null).count();
                if (apply) automaticImages.refreshWorldSettingImages(subjects);
            }
            if (apply) entityManager.flush();
        } finally {
            session.setJdbcBatchSize(previousBatchSize);
        }
        return new WorldImageBackfillResponse(ids.size(), matched, ids.size() - matched, apply);
    }
}
