package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.LoanPurpose;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPurposeRepository extends JpaRepository<LoanPurpose, String> {

    List<LoanPurpose> findAllByActiveTrueOrderByCode();
}
