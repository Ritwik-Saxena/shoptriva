package com.shoptriva.catalog.service;

import com.shoptriva.catalog.dto.CategoryRequest;
import com.shoptriva.catalog.dto.CategoryResponse;

import java.util.List;

public interface CategoryService {

    CategoryResponse createCategory(CategoryRequest request);

    List<CategoryResponse> getAllCategories();

    CategoryResponse getCategoryById(Long id);
}