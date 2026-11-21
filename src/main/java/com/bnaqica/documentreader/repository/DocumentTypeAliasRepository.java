package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.DocumentTypeAlias;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTypeAliasRepository extends JpaRepository<DocumentTypeAlias, UUID> {

    List<DocumentTypeAlias> findAllByDocumentTypeIdOrderByAlias(UUID documentTypeId);

    Optional<DocumentTypeAlias> findByIdAndDocumentTypeId(UUID id, UUID documentTypeId);
}
