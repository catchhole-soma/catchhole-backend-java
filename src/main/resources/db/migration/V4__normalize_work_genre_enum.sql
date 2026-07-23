UPDATE works
SET genre = CASE genre
    WHEN '판타지' THEN 'FANTASY'
    WHEN '로맨스' THEN 'ROMANCE'
    WHEN '추리' THEN 'MYSTERY'
    WHEN '코미디' THEN 'COMEDY'
    WHEN 'SF' THEN 'SF'
    WHEN '스포츠' THEN 'SPORTS'
    WHEN '호러' THEN 'HORROR'
    WHEN '무협' THEN 'MARTIAL_ARTS'
    WHEN '일상' THEN 'SLICE_OF_LIFE'
    WHEN '기타' THEN 'ETC'
    WHEN 'FANTASY' THEN 'FANTASY'
    WHEN 'ROMANCE' THEN 'ROMANCE'
    WHEN 'MYSTERY' THEN 'MYSTERY'
    WHEN 'COMEDY' THEN 'COMEDY'
    WHEN 'SPORTS' THEN 'SPORTS'
    WHEN 'HORROR' THEN 'HORROR'
    WHEN 'MARTIAL_ARTS' THEN 'MARTIAL_ARTS'
    WHEN 'SLICE_OF_LIFE' THEN 'SLICE_OF_LIFE'
    WHEN 'ETC' THEN 'ETC'
    ELSE 'ETC'
END;

ALTER TABLE works
    ALTER COLUMN genre SET NOT NULL;

ALTER TABLE works
    ADD CONSTRAINT chk_works_genre
        CHECK (genre IN (
            'FANTASY',
            'ROMANCE',
            'MYSTERY',
            'COMEDY',
            'SF',
            'SPORTS',
            'HORROR',
            'MARTIAL_ARTS',
            'SLICE_OF_LIFE',
            'ETC'
        ));
