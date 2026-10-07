package com.withus.campaign.controller;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.withus.campaign.domain.FileErrorCode;
import com.withus.campaign.dto.FileUploadResponse;
import com.withus.campaign.service.FileStorage;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "파일", description = "템플릿 에디터 이미지 업로드 (API_SPEC 5장)")
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

	private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;
	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/gif");
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif");

	private final FileStorage fileStorage;

	public FileController(FileStorage fileStorage) {
		this.fileStorage = fileStorage;
	}

	@Operation(summary = "이미지 업로드", description = "jpg·png·gif, 최대 5MB. GET 가능한 공개 URL을 돌려준다.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@PostMapping("/images")
	public ApiResponse<FileUploadResponse> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "파일이 비어 있습니다.", null);
		}
		if (file.getSize() > MAX_IMAGE_SIZE) {
			throw new BusinessException(FileErrorCode.UPLOAD_FILE_TOO_LARGE);
		}
		if (!isAllowedType(file)) {
			throw new BusinessException(FileErrorCode.FILE_INVALID_TYPE);
		}
		String key = fileStorage.store(file.getOriginalFilename(), file.getInputStream(), file.getContentType());
		return ApiResponse.ok(new FileUploadResponse(fileStorage.publicUrl(key)));
	}

	/** 확장자와 Content-Type을 함께 검사한다 — 둘 중 하나만으로는 위조가 쉽다 */
	private boolean isAllowedType(MultipartFile file) {
		String contentType = file.getContentType();
		if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
			return false;
		}
		String filename = file.getOriginalFilename();
		if (filename == null) {
			return false;
		}
		String lower = filename.toLowerCase(Locale.ROOT);
		return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
	}
}
