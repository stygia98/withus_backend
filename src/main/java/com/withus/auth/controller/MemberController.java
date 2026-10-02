package com.withus.auth.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

	@Operation(summary = "역할·활성 여부 변경", description = "보내지 않은 값은 그대로. 바꾸면 그 사용자는 다시 로그인해야 한다"
		+ "(늦어도 30분 안에 반영). 자기 계정은 MEMBER_SELF_CHANGE(400)")
	@PatchMapping("/{memberId}")
	public ApiResponse<MemberAdminResponse> update(@PathVariable long memberId,
		@RequestBody MemberUpdateRequest request, @AuthenticationPrincipal AuthMember me) {
		return ApiResponse.ok(memberService.update(memberId, request, me.memberId()));
	}
}
