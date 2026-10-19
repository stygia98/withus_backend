package com.withus.auth.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.auth.dto.MemberAdminResponse;
import com.withus.auth.dto.MemberCreateRequest;
import com.withus.auth.dto.MemberUpdateRequest;
import com.withus.auth.dto.PasswordResetRequest;
import com.withus.auth.security.AuthMember;
import com.withus.auth.service.MemberService;
import com.withus.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "사용자 관리", description = "관리자 계정·역할 (API_SPEC 2장, PRD 3장 OWNER 전용)")
@RestController
@RequestMapping("/api/v1/members")
@PreAuthorize("hasRole('OWNER')")
public class MemberController {

	private final MemberService memberService;

	public MemberController(MemberService memberService) {
		this.memberService = memberService;
	}

	@Operation(summary = "사용자 목록", description = "member_id 순. 사용자 수가 적어 페이징하지 않는다")
	@GetMapping
	public ApiResponse<List<MemberAdminResponse>> list() {
		return ApiResponse.ok(memberService.list());
	}

	@Operation(summary = "사용자 생성", description = "초기 비밀번호 8~72자. 오류: MEMBER_DUPLICATE_EMAIL(409)")
	@PostMapping
	public ApiResponse<MemberAdminResponse> create(@Valid @RequestBody MemberCreateRequest request) {
		return ApiResponse.ok(memberService.create(request));
	}

	@Operation(summary = "계정 수정 (이름·이메일·역할·활성 여부)", description = "보내지 않은 값은 그대로. 역할·활성 여부를 바꾸면"
		+ " 그 사용자는 다시 로그인해야 한다(늦어도 30분 안에 반영). 이름·이메일은 자기 계정도 바꿀 수 있고, 자기 역할·활성 여부는"
		+ " MEMBER_SELF_CHANGE(400). 이메일 중복 MEMBER_DUPLICATE_EMAIL(409)")
	@PatchMapping("/{memberId}")
	public ApiResponse<MemberAdminResponse> update(@PathVariable long memberId,
		@Valid @RequestBody MemberUpdateRequest request, @AuthenticationPrincipal AuthMember me) {
		return ApiResponse.ok(memberService.update(memberId, request, me.memberId()));
	}

	@Operation(summary = "계정 삭제 (삭제 표시)", description = "행은 남기고(만든 캠페인 등의 작성자 기록 유지) 목록·로그인에서 뺀다."
		+ " 그 사용자의 로그인은 끊긴다(Access 는 최대 30분 유효). 같은 이메일로 새 계정을 만들 수 있다. 자기 계정은 MEMBER_SELF_DELETE(400)")
	@DeleteMapping("/{memberId}")
	public ApiResponse<Void> delete(@PathVariable long memberId, @AuthenticationPrincipal AuthMember me) {
		memberService.delete(memberId, me.memberId());
		return ApiResponse.ok(null);
	}

	@Operation(summary = "삭제한 계정 목록", description = "최근 삭제순. 복구할 계정을 고를 때 쓴다")
	@GetMapping("/deleted")
	public ApiResponse<List<MemberAdminResponse>> deletedList() {
		return ApiResponse.ok(memberService.deletedList());
	}

	@Operation(summary = "삭제한 계정 복구", description = "삭제 표시를 지워 예전 비밀번호로 다시 로그인할 수 있게 한다."
		+ " 같은 이메일의 계정이 다시 생겼으면 MEMBER_DUPLICATE_EMAIL(409), 삭제된 계정이 아니면 COMMON_NOT_FOUND(404)")
	@PostMapping("/{memberId}/restore")
	public ApiResponse<MemberAdminResponse> restore(@PathVariable long memberId) {
		return ApiResponse.ok(memberService.restore(memberId));
	}

	@Operation(summary = "비밀번호 재설정", description = "새 비밀번호 8~72자(OWNER 가 정해 전달). 그 사용자의 기존 로그인은 끊기고"
		+ " 실패 잠금도 풀린다. 기존 비밀번호는 조회할 수 없다. 자기 계정은 MEMBER_SELF_PASSWORD(400)")
	@PostMapping("/{memberId}/password")
	public ApiResponse<Void> resetPassword(@PathVariable long memberId,
		@Valid @RequestBody PasswordResetRequest request, @AuthenticationPrincipal AuthMember me) {
		memberService.resetPassword(memberId, request, me.memberId());
		return ApiResponse.ok(null);
	}
}
