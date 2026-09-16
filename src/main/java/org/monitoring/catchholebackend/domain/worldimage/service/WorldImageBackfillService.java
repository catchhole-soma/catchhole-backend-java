package org.monitoring.catchholebackend.domain.worldimage.service;

import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldImageBackfillRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageBackfillResponse;

public interface WorldImageBackfillService {
    WorldImageBackfillResponse backfillImages(WorldImageBackfillRequest request);
}
