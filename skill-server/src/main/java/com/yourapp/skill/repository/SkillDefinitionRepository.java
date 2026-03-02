package com.yourapp.skill.repository;

import com.yourapp.skill.model.SkillDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SkillDefinitionRepository {

    List<SkillDefinition> findAll();

    Optional<SkillDefinition> findById(@Param("id") Long id);

    Optional<SkillDefinition> findBySkillCode(@Param("skillCode") String skillCode);

    List<SkillDefinition> findByStatus(@Param("status") String status);

    List<SkillDefinition> findByStatusOrderBySortOrderAsc(@Param("status") String status);

    int insert(SkillDefinition definition);

    int update(SkillDefinition definition);
}
