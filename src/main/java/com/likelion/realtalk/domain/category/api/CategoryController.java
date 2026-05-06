package com.likelion.realtalk.domain.category.api;

import com.likelion.realtalk.domain.category.dto.response.CategoryResponse;
import com.likelion.realtalk.domain.category.service.CategoryService;
import com.likelion.realtalk.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAll() {
        return ApiResponse.ok(categoryService.getAll());
    }
}
