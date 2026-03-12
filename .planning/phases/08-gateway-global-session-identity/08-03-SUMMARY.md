# Plan 08-03 Summary

## Outcome

`ai-gateway` 的主键治理已切到 Snowflake：

- `agent_connection.id`
- `ak_sk_credential.id`

`AgentRegistryService.register` 已在新建记录时预先生成 Snowflake 主键，同时保留既有“同 AK + toolType 复用旧记录”的身份复用逻辑。

## Key Files

- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java`
- `ai-gateway/src/main/resources/mapper/AgentConnectionMapper.xml`
- `ai-gateway/src/main/resources/mapper/AkSkCredentialMapper.xml`
- `ai-gateway/src/main/resources/db/migration/V5__snowflake_primary_keys.sql`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/AgentRegistryServiceTest.java`

## Verification

- `mvn test -Dtest=AgentRegistryServiceTest`
- `mvn test`

## Notes

- 本计划重点验证了“新建”和“复用”两条路径，避免只验证 insert 场景。
- `findById`、`findLatestByAkId` 等以 `Long` 主键访问的路径保持兼容。
