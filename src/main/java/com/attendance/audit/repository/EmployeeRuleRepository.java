package com.attendance.audit.repository;

import com.attendance.audit.model.EmployeeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeRuleRepository extends JpaRepository<EmployeeRule, String> {
}
