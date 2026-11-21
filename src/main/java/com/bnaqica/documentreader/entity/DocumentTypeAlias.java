package com.bnaqica.documentreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Another name that resolves to a document type, such as "Earnings statement" for "Pay stubs". */
@Entity
@Table(schema = "document_reader", name = "document_type_alias")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
@AllArgsConstructor
public class DocumentTypeAlias {

    @Id
    private UUID id;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(nullable = false)
    private String alias;

    @Column(name = "normalized_key", nullable = false)
    private String normalizedKey;
}
