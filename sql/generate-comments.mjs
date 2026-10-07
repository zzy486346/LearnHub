import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const root = dirname(fileURLToPath(import.meta.url))
const dictionary = JSON.parse(readFileSync(resolve(root, 'schema-comments.json'), 'utf8'))
const mysql = sql => execFileSync('docker', ['exec', 'deploy-mysql-1', 'sh', '-c',
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B -e "$1"', 'mysql', sql],
  { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
const sqlQuote = value => "'" + value.replaceAll("'", "''") + "'"
function comment(table, column) {
  const value = dictionary.overrides[`${table}.${column}`] || dictionary.columns[column]
  if (!value || !/[\u4e00-\u9fff]/u.test(value)) throw new Error(`缺少中文字段注释：${table}.${column}`)
  return value
}
function annotate(source) {
  source = source.replace(/\r\n|\r/g, '\n')
  return source.replace(/CREATE TABLE IF NOT EXISTS (\w+) \(([\s\S]*?)\n\)(?: COMMENT='[^']*')?;/g, (block, table, body) => {
    if (!dictionary.tables[table]) throw new Error(`缺少中文表注释：${table}`)
    const annotated = body.split('\n').map(line => {
      const match = line.match(/^    (\w+) (BIGINT|VARCHAR|INT|DATETIME|TINYINT|TEXT|DECIMAL)\b/i)
      if (!match) return line
      const comma = line.endsWith(',') ? ',' : ''
      const definition = (comma ? line.slice(0, -1) : line).replace(/ COMMENT '[^']*'$/, '')
      return definition + ' COMMENT ' + sqlQuote(comment(table, match[1])) + comma
    }).join('\n')
    return `CREATE TABLE IF NOT EXISTS ${table} (${annotated}\n) COMMENT=${sqlQuote(dictionary.tables[table])};`
  })
}
function fields(source) {
  const specs = []
  for (const match of source.matchAll(/CREATE TABLE IF NOT EXISTS (\w+) \(([\s\S]*?)\n\)/g)) {
    const table = match[1]
    specs.push([table, '', dictionary.tables[table]])
    for (const line of match[2].split('\n')) {
      const column = line.match(/^    (\w+) (BIGINT|VARCHAR|INT|DATETIME|TINYINT|TEXT|DECIMAL)\b/i)?.[1]
      if (column) specs.push([table, column, comment(table, column)])
    }
  }
  return specs
}
function migration(specs) {
  return `-- 为现有 LearnHub 表和字段补齐中文注释，可重复执行。
-- 字段定义读取自 information_schema，保留各环境实际类型、默认值和其他属性。
-- 不修改业务数据；已有中文注释保留。执行前建议备份表结构。
SET NAMES utf8mb4;
USE learnhub;
CREATE TEMPORARY TABLE learnhub_comment_specs (
    table_name VARCHAR(64) NOT NULL COMMENT '业务表名',
    column_name VARCHAR(64) NOT NULL COMMENT '业务字段名，空字符串表示表注释',
    chinese_comment VARCHAR(512) NOT NULL COMMENT '待补充的中文注释',
    PRIMARY KEY (table_name, column_name)
) COMMENT='中文数据库注释临时映射表';
INSERT INTO learnhub_comment_specs VALUES
${specs.map(row => '    (' + row.map(sqlQuote).join(', ') + ')').join(',\n')};

DELIMITER $$
CREATE PROCEDURE learnhub_comment_v20261007()
BEGIN
    DECLARE finished INT DEFAULT 0;
    DECLARE table_name_value VARCHAR(64);
    DECLARE column_name_value VARCHAR(64);
    DECLARE definition_value TEXT;
    DECLARE current_table VARCHAR(64) DEFAULT '';
    DECLARE table_comment_value VARCHAR(512);
    DECLARE table_comment_existing TEXT;
    DECLARE statement_value LONGTEXT DEFAULT '';
    DECLARE column_cursor CURSOR FOR
        SELECT c.TABLE_NAME, c.COLUMN_NAME,
               CONCAT('MODIFY COLUMN \`', c.COLUMN_NAME, '\` ', c.COLUMN_TYPE,
                 IF(c.CHARACTER_SET_NAME IS NULL, '', CONCAT(' CHARACTER SET ', c.CHARACTER_SET_NAME, ' COLLATE ', c.COLLATION_NAME)),
                 IF(c.IS_NULLABLE = 'NO', ' NOT NULL', ' NULL'),
                 CASE WHEN c.COLUMN_DEFAULT IS NULL THEN IF(c.IS_NULLABLE = 'YES', ' DEFAULT NULL', '')
                      WHEN c.EXTRA LIKE '%DEFAULT_GENERATED%' THEN CONCAT(' DEFAULT ', c.COLUMN_DEFAULT)
                      ELSE CONCAT(' DEFAULT ', QUOTE(c.COLUMN_DEFAULT)) END,
                 IF(TRIM(REPLACE(c.EXTRA, 'DEFAULT_GENERATED', '')) = '', '', CONCAT(' ', TRIM(REPLACE(c.EXTRA, 'DEFAULT_GENERATED', '')))),
                 ' COMMENT ', QUOTE(s.chinese_comment))
        FROM information_schema.COLUMNS c
        JOIN learnhub_comment_specs s ON s.table_name = c.TABLE_NAME AND s.column_name = c.COLUMN_NAME
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.COLUMN_COMMENT NOT REGEXP '[一-龥]'
        ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION;
    DECLARE table_cursor CURSOR FOR
        SELECT t.TABLE_NAME, s.chinese_comment FROM information_schema.TABLES t
        JOIN learnhub_comment_specs s ON s.table_name = t.TABLE_NAME AND s.column_name = ''
        WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_COMMENT NOT REGEXP '[一-龥]';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = 1;
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS c
               WHERE c.TABLE_SCHEMA = DATABASE() AND c.COLUMN_COMMENT NOT REGEXP '[一-龥]'
                 AND (c.GENERATION_EXPRESSION <> '' OR NOT EXISTS (
                   SELECT 1 FROM learnhub_comment_specs s WHERE s.table_name = c.TABLE_NAME AND s.column_name = c.COLUMN_NAME))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '字段注释映射不完整或存在生成列，请先核对结构';
    END IF;
    OPEN column_cursor;
    comment_loop: LOOP
        FETCH column_cursor INTO table_name_value, column_name_value, definition_value;
        IF finished = 1 THEN LEAVE comment_loop; END IF;
        IF current_table <> table_name_value THEN
            IF current_table <> '' THEN
                SET @learnhub_comment_sql = statement_value;
                PREPARE comment_stmt FROM @learnhub_comment_sql;
                EXECUTE comment_stmt;
                DEALLOCATE PREPARE comment_stmt;
            END IF;
            SET current_table = table_name_value;
            SELECT s.chinese_comment, t.TABLE_COMMENT INTO table_comment_value, table_comment_existing
            FROM learnhub_comment_specs s JOIN information_schema.TABLES t ON t.TABLE_NAME = s.table_name
            WHERE s.table_name = current_table AND s.column_name = '' AND t.TABLE_SCHEMA = DATABASE();
            SET statement_value = CONCAT('ALTER TABLE \`', current_table, '\` COMMENT = ',
                QUOTE(IF(table_comment_existing REGEXP '[一-龥]', table_comment_existing, table_comment_value)));
        END IF;
        SET statement_value = CONCAT(statement_value, ', ', definition_value);
    END LOOP;
    CLOSE column_cursor;
    IF current_table <> '' THEN
        SET @learnhub_comment_sql = statement_value;
        PREPARE comment_stmt FROM @learnhub_comment_sql;
        EXECUTE comment_stmt;
        DEALLOCATE PREPARE comment_stmt;
    END IF;
    SET finished = 0;
    OPEN table_cursor;
    table_loop: LOOP
        FETCH table_cursor INTO table_name_value, table_comment_value;
        IF finished = 1 THEN LEAVE table_loop; END IF;
        SET @learnhub_comment_sql = CONCAT('ALTER TABLE \`', table_name_value, '\` COMMENT = ', QUOTE(table_comment_value));
        PREPARE comment_stmt FROM @learnhub_comment_sql;
        EXECUTE comment_stmt;
        DEALLOCATE PREPARE comment_stmt;
    END LOOP;
    CLOSE table_cursor;
END$$
DELIMITER ;
CALL learnhub_comment_v20261007();
DROP PROCEDURE learnhub_comment_v20261007;
DROP TEMPORARY TABLE learnhub_comment_specs;

-- 验收：以下两项均应为0。
SELECT COUNT(*) AS tables_without_chinese_comments FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'learnhub' AND TABLE_TYPE = 'BASE TABLE' AND TABLE_COMMENT NOT REGEXP '[一-龥]';
SELECT COUNT(*) AS columns_without_chinese_comments FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'learnhub' AND COLUMN_COMMENT NOT REGEXP '[一-龥]';
-- 回滚：本脚本只增加说明信息，无业务回滚要求；如需恢复旧注释，使用执行前的结构备份。
`
}

if (process.argv[2] === '--patch') {
  let patch = '*** Begin Patch\n'
  for (const relative of ['schema.sql', 'patch_course_order.sql', 'patch_media_asset.sql', 'migrations/V20260930__async_like_pipeline.sql']) {
    const path = resolve(root, relative).replaceAll('\\', '/')
    const old = readFileSync(path, 'utf8')
    const updated = annotate(old)
    if (old.replace(/\r\n|\r/g, '\n') === updated) continue
    patch += `*** Update File: ${path}\n@@\n${old.trimEnd().split(/\r\n|\n|\r/).map(line => '-' + line).join('\n')}\n${updated.trimEnd().split(/\r\n|\n|\r/).map(line => '+' + line).join('\n')}\n`
  }
  const path = resolve(root, 'migrations/V20261007__chinese_schema_comments.sql').replaceAll('\\', '/')
  const generated = migration(fields(readFileSync(resolve(root, 'schema.sql'), 'utf8')))
  if (existsSync(path)) {
    const old = readFileSync(path, 'utf8')
    if (old.replace(/\r\n|\r/g, '\n') !== generated) patch += `*** Update File: ${path}\n@@\n${old.trimEnd().split(/\r?\n/).map(line => '-' + line).join('\n')}\n${generated.trimEnd().split('\n').map(line => '+' + line).join('\n')}\n`
  } else {
    patch += `*** Add File: ${path}\n${generated.trimEnd().split('\n').map(line => '+' + line).join('\n')}\n`
  }
  patch += '*** End Patch'
  process.stdout.write(patch)
} else if (process.argv[2] === '--check') {
  const source = readFileSync(resolve(root, 'schema.sql'), 'utf8').replace(/\r\n|\r/g, '\n')
  if (annotate(source) !== source) throw new Error('建表基线与中文注释映射不一致')
  const specs = fields(source)
  console.log(JSON.stringify({ tables: specs.filter(row => !row[1]).length, columns: specs.filter(row => row[1]).length, result: '中文注释检查通过' }))
} else if (process.argv[2] === '--snapshot') {
  const query = mysql
  const tables = query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='learnhub' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").split('\n')
  const definitions = query("SELECT JSON_ARRAY(TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_KEY,GENERATION_EXPRESSION,ORDINAL_POSITION) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='learnhub' ORDER BY TABLE_NAME,ORDINAL_POSITION")
  const indexes = query("SELECT JSON_ARRAY(TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,SUB_PART,INDEX_TYPE,IS_VISIBLE) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='learnhub' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX")
  const tableAttributes = query("SELECT JSON_ARRAY(TABLE_NAME,ENGINE,ROW_FORMAT,TABLE_COLLATION,AUTO_INCREMENT,CREATE_OPTIONS) FROM information_schema.TABLES WHERE TABLE_SCHEMA='learnhub' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")
  const checksums = query('CHECKSUM TABLE ' + tables.map(table => 'learnhub.`' + table + '`').join(', ') + ' EXTENDED')
  const counts = tables.map(table => [table, query(`SELECT COUNT(*) FROM learnhub.\`${table}\``)])
  process.stdout.write(JSON.stringify({ tables, definitions, indexes, tableAttributes, checksums, counts }))
} else if (process.argv[2] === '--validate-schema') {
  const databases = []
  const old = execFileSync('git', ['show', 'HEAD:sql/schema.sql'], { cwd: resolve(root, '..'), encoding: 'utf8' })
  const current = readFileSync(resolve(root, 'schema.sql'), 'utf8')
  const structures = []
  try {
    for (const source of [old, current]) {
      const database = 'learnhub_comments_verify_' + randomUUID().replaceAll('-', '')
      mysql(`CREATE DATABASE \`${database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`)
      databases.push(database)
      mysql(source.replace(/CREATE DATABASE IF NOT EXISTS learnhub[^;]*;/, '')
        .replace(/USE learnhub;/, `USE \`${database}\`;`))
      structures.push(mysql(`SELECT JSON_ARRAY(TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,COLUMN_KEY,CHARACTER_SET_NAME,COLLATION_NAME,ORDINAL_POSITION)
        FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${database}' ORDER BY TABLE_NAME,ORDINAL_POSITION`))
    }
    assert.equal(structures[0], structures[1], '建表基线除注释外发生了字段属性变化')
    const fresh = databases[1]
    assert.equal(mysql(`SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${fresh}' AND TABLE_COMMENT NOT REGEXP '[一-龥]'`), '0')
    assert.equal(mysql(`SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${fresh}' AND COLUMN_COMMENT NOT REGEXP '[一-龥]'`), '0')
    console.log('新旧建表脚本在独立空库执行成功，字段属性一致，中文表及字段注释完整')
  } finally {
    for (const database of databases) {
      if (!/^learnhub_comments_verify_[a-f0-9]{32}$/.test(database)) throw new Error('拒绝清理非本次验证数据库')
      mysql(`DROP DATABASE \`${database}\``)
    }
    console.log('本次创建的临时验证数据库已清理')
  }
} else {
  throw new Error('使用 --patch 生成补丁、--check 检查基线、--snapshot 核对数据库或 --validate-schema 验证空库建表')
}
