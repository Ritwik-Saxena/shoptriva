package com.shoptriva.catalog.service;

import com.shoptriva.catalog.dto.*;
import com.shoptriva.catalog.entity.Category;
import com.shoptriva.catalog.mapper.CategoryMapper;
import com.shoptriva.catalog.repository.CategoryRepository;
import com.shoptriva.common.exception.CategoryNotFoundException;
import com.shoptriva.common.exception.DuplicateCategoryException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    @Override
    public CategoryResponse createCategory(CategoryRequest request) {

        if (categoryRepository.existsByName(request.getName())) {
            throw new DuplicateCategoryException(request.getName());
        }

        Category category = CategoryMapper.toEntity(request);

        Category saved = categoryRepository.save(category);

        return CategoryMapper.toResponse(saved);
    }

    @Override
    public List<CategoryResponse> getAllCategories() {

        return categoryRepository.findAll()
                .stream()
                .map(CategoryMapper::toResponse)
                .toList();
    }

    @Override
    public CategoryResponse getCategoryById(Long id) {

        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));

        return CategoryMapper.toResponse(category);
    }
}