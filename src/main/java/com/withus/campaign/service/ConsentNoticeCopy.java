package com.withus.campaign.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.withus.common.render.HtmlEscaper;

/**
 * 수신동의 2년 주기 확인 안내(F-12, kind=NOTICE)의 고정 문구 (PL 결정 #81: 코드 고정 문구 + 치환).
 * 필수 요소는 PRD 5.1 F-12·8.4 의 세 가지다 — 전송자 명칭, 수신동의 날짜와 동의 사실, 수신거부 방법.
 * 광고가 아니므로 (광고) 표기와 연락처 문구는 넣지 않는다. 템플릿이 없고 외부 상태·DB 접근도 없는 순수 클래스다.
 * 세부 문구는 PRD 가 KISA 안내서로 확인하라고 한 부분이라, 바뀌면 이 클래스만 고친다
 */
@Component
public class ConsentNoticeCopy {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREA);

	private final String senderName;
	private final String unsubscribePhone;

	public ConsentNoticeCopy(@Value("${withus.sender.name}") String senderName,
			@Value("${withus.sender.unsubscribe-phone}") String unsubscribePhone) {
		this.senderName = senderName;
		this.unsubscribePhone = unsubscribePhone;
	}

	public String emailSubject() {
		return "[" + senderName + "] 수신동의 확인 안내";
	}

	/** 수신거부 링크는 확인 화면 주소다 — 추적 치환하지 않는다(CLAUDE.md 6장 7번) */
	public String emailBody(OffsetDateTime consentAt, String unsubscribeUrl) {
		String sender = HtmlEscaper.escape(senderName);
		return "<p>안녕하세요, " + sender + "입니다.</p>"
			+ "<p>" + sender + "에서는 고객님께서 " + formatDate(consentAt) + "에 이메일 광고성 정보 수신에 동의하신 사실을 안내드립니다. "
			+ "별도의 조치를 하지 않으시면 동의는 유지됩니다.</p>"
			+ "<p>수신을 원하지 않으시면 언제든지 수신거부하실 수 있습니다.<br>"
			+ "수신거부: <a href=\"" + HtmlEscaper.escape(unsubscribeUrl) + "\">수신거부 신청</a></p>";
	}

	/** SMS 는 링크 대신 무료 수신거부 번호로 수신거부 방법을 알린다(PRD 8.4) */
	public String sms(OffsetDateTime consentAt) {
		return senderName + " 수신동의 안내: " + formatDate(consentAt) + "에 문자 광고성 정보 수신에 동의하셨습니다. "
			+ "조치가 없으시면 동의가 유지됩니다.\n무료수신거부 " + unsubscribePhone;
	}

	/** 동의 일시는 서울 기준 날짜로 보여준다(JVM·DB 타임존과 무관하게 고객이 기억하는 날짜) */
	private String formatDate(OffsetDateTime consentAt) {
		return consentAt.atZoneSameInstant(SEOUL).format(DATE);
	}
}
