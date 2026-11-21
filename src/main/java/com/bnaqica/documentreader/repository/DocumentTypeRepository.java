package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.DocumentType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    /** Case-insensitive by name, whatever the database collation, so "Valuation report" sorts before "VIN". */
    @Query("SELECT t FROM DocumentType t ORDER BY lower(t.name)")
    List<DocumentType> findAllOrderedByName();

    /** The type whose own key, or one of whose aliases' keys, is {@code key}. Keys are unique across both. */
    @Query("""
            SELECT t FROM DocumentType t
            WHERE t.normalizedKey = :key
               OR t.id IN (SELECT a.documentTypeId FROM DocumentTypeAlias a WHERE a.normalizedKey = :key)
            """)
    Optional<DocumentType> findByKeyOrAliasKey(@Param("key") String key);
}
