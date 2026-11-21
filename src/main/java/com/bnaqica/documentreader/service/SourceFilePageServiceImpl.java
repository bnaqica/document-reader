package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.PageImage;
import com.bnaqica.documentreader.dto.response.PageRecord;
import com.bnaqica.documentreader.entity.SourceFilePage;
import com.bnaqica.documentreader.entity.SourceFilePageId;
import com.bnaqica.documentreader.repository.SourceFilePageRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceFilePageServiceImpl implements SourceFilePageService {

    private final SourceFilePageRepository repository;

    @Override
    @Transactional
    public void saveAll(UUID sourceFileId, List<PageImage> pages) {
        repository.deleteBySourceFile(sourceFileId);
        repository.saveAll(pages.stream()
                .map(page -> SourceFilePage.builder()
                        .id(new SourceFilePageId(sourceFileId, page.pageNumber()))
                        .s3Key(page.s3Key())
                        .width(page.width())
                        .height(page.height())
                        .build())
                .toList());
        log.info("Saved {} pages for source file {}", pages.size(), sourceFileId);
    }

    @Override
    public List<PageRecord> findBySourceFile(UUID sourceFileId) {
        return repository.findBySourceFile(sourceFileId).stream()
                .map(page -> PageRecord.builder()
                        .sourceFileId(page.getId().getSourceFileId())
                        .pageNumber(page.getId().getPageNumber())
                        .s3Key(page.getS3Key())
                        .width(page.getWidth())
                        .height(page.getHeight())
                        .build())
                .toList();
    }
}
