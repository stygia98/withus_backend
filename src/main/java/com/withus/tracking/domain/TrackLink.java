package com.withus.tracking.domain;

import lombok.Getter;

/** track_link 행 중 링크 치환에 필요한 열 */
@Getter
public class TrackLink {

	private Long linkId;
	private String originalUrl;
}
