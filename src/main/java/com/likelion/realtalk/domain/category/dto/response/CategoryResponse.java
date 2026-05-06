package com.likelion.realtalk.domain.category.dto.response;

import com.likelion.realtalk.domain.category.entity.Category;

public record CategoryResponse(Long id, String categoryName) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getCategoryName());
    }
}
