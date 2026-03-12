# Plan 08-02 Summary

## Outcome

`skill-server` 的核心持久化主键链路已切换到应用侧 Snowflake 生成：

- `skill_session.id`
- `skill_message.id`
- `skill_message_part.id`

同时移除了 mapper 对 `useGeneratedKeys` 和数据库自增回填的依赖。

## Key Files

- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
- `skill-server/src/main/resources/mapper/SkillSessionMapper.xml`
- `skill-server/src/main/resources/mapper/SkillMessageMapper.xml`
- `skill-server/src/main/resources/mapper/SkillMessagePartMapper.xml`
- `skill-server/src/main/resources/db/migration/V5__snowflake_primary_keys.sql`

## Verification

- `mvn test -Dtest=SkillSessionServiceTest,SkillMessageServiceTest,MessagePersistenceServiceTest`
- `mvn test`

## Notes

- `welinkSessionId` 继续沿用 `Long`，但其生成来源已从 DB 自增切换为 Snowflake。
- 协议级 `messageId` / `partId` 仍保留为业务语义字段，不与 DB 主键混淆。
