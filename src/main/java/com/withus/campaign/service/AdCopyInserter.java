package com.withus.campaign.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.withus.common.render.HtmlEscaper;

/**
 * 광고성(template.ad_yn=Y) 메시지에 (광고) 표기·발신자 정보·수신거부 안내를 시스템이 자동으로 넣는다(PRD 8.4).
 * 템플릿 작성자가 지울 수 없다. 비광고 메시지는 그대로 돌려준다. 발송 직전 렌더링과 미리보기 양쪽에서 재사용하는
 * 순수 클래스라 외부 상태·DB 접근이 없다. 수신거부 링크 URL은 이미 만들어진 값을 받는다 — 토큰 생성 주체(PRD 8.3
 * HMAC)는 PL이 common에 공용 유틸로 제공할 예정이라 여기서 추측해 만들지 않는다.
 */
@Component
public class AdCopyInserter {

	private final String senderName;
	private final String senderPhone;
	private final String unsubscribePhone;

	public AdCopyInserter(@Value("${withus.sender.name}") String senderName,
			@Value("${withus.sender.phone}") String senderPhone,
			@Value("${withus.sender.unsubscribe-phone}") String unsubscribePhone) {
		this.senderName = senderName;
		this.senderPhone = senderPhone;
		this.unsubscribePhone = unsubscribePhone;
	}

	/** 메일 제목 앞에 (광고) 를 붙인다 */
	public String insertSubject(String subject, boolean isAd) {
		return isAd ? "(광고) " + subject : subject;
	}

	/** 메일 본문(HTML) 하단에 발신자 명칭·연락처·수신거부 링크를 붙인다 */
	public String insertEmailBody(String htmlBody, boolean isAd, String unsubscribeUrl) {
		if (!isAd) {
			return htmlBody;
		}
		return htmlBody + "<hr><p>" + HtmlEscaper.escape(senderName) + " | " + HtmlEscaper.escape(senderPhone)
			+ "<br>수신거부: <a href=\"" + HtmlEscaper.escape(unsubscribeUrl) + "\">수신거부 신청</a></p>";
	}

	/** SMS 본문 맨 앞에 (광고)·발신자, 끝에 무료수신거부 번호를 붙인다 */
	public String insertSms(String body, boolean isAd) {
		if (!isAd) {
			return body;
		}
		return "(광고)" + senderName + " " + body + "\n무료수신거부 " + unsubscribePhone;
	}
}
