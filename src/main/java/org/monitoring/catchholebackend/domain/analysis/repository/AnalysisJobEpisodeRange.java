package org.monitoring.catchholebackend.domain.analysis.repository;

public interface AnalysisJobEpisodeRange {

    Integer getEpisodeStartNo();

    Integer getEpisodeEndNo();

    long getEpisodeCount();
}
