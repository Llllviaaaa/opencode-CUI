package com.yourapp.gateway.repository;

import com.yourapp.gateway.model.AgentConnection;
import com.yourapp.gateway.model.AgentConnection.AgentStatus;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AgentConnectionRepository {

    /** Find all agents with a given status */
    @Select("SELECT * FROM agent_connection WHERE status = #{status}")
    List<AgentConnection> findByStatus(AgentStatus status);

    /** Find agents by user ID */
    @Select("SELECT * FROM agent_connection WHERE user_id = #{userId}")
    List<AgentConnection> findByUserId(Long userId);

    /** Find an existing online connection for the same AK and tool type (for kick-old logic) */
    @Select("SELECT * FROM agent_connection WHERE ak_id = #{akId} AND tool_type = #{toolType} AND status = #{status} LIMIT 1")
    Optional<AgentConnection> findByAkIdAndToolTypeAndStatus(String akId, String toolType, AgentStatus status);

    /** Find stale agents: online but last_seen_at older than the given threshold */
    @Select("SELECT * FROM agent_connection WHERE status = 'ONLINE' AND last_seen_at < #{threshold}")
    List<AgentConnection> findStaleAgents(LocalDateTime threshold);

    /** Bulk mark stale agents offline */
    @Update("UPDATE agent_connection SET status = 'OFFLINE' WHERE status = 'ONLINE' AND last_seen_at < #{threshold}")
    int markStaleAgentsOffline(LocalDateTime threshold);

    /** Insert a new agent connection record */
    @Insert("INSERT INTO agent_connection (user_id, ak_id, device_name, os, tool_type, tool_version, status, last_seen_at, created_at) " +
            "VALUES (#{userId}, #{akId}, #{deviceName}, #{os}, #{toolType}, #{toolVersion}, #{status}, #{lastSeenAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentConnection agent);

    /** Find by primary key */
    @Select("SELECT * FROM agent_connection WHERE id = #{id}")
    Optional<AgentConnection> findById(Long id);

    /** Update status for an agent */
    @Update("UPDATE agent_connection SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") AgentStatus status);

    /** Update last_seen_at for an agent */
    @Update("UPDATE agent_connection SET last_seen_at = #{lastSeenAt} WHERE id = #{id}")
    int updateLastSeenAt(@Param("id") Long id, @Param("lastSeenAt") LocalDateTime lastSeenAt);

    /** Delete by primary key */
    @Delete("DELETE FROM agent_connection WHERE id = #{id}")
    int deleteById(Long id);
}
