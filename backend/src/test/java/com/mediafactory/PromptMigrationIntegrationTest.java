package com.mediafactory;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class PromptMigrationIntegrationTest {
 @Test void upgradeFromTaskTwoPreservesLegacyPromptsWithoutInventingAttribution() throws Exception {
  try(var postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))){postgres.start();
   Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("3").load().migrate();
   UUID project=UUID.randomUUID(),collection=UUID.randomUUID(),concept=UUID.randomUUID(),generation=UUID.randomUUID();
   try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var sql=connection.createStatement()){
    sql.execute("insert into projects(id,name) values('"+project+"','Old project')");sql.execute("insert into collections(id,project_id,name) values('"+collection+"','"+project+"','Old collection')");sql.execute("insert into concepts(id,collection_id,name,prompt) values('"+concept+"','"+collection+"','Old concept','A wolf')");
    sql.execute("insert into generations(id,concept_id,status,prompt,width,height,selected_provider) values('"+generation+"','"+concept+"','QUEUED','A legacy wolf',128,128,'mock')");
   }
   Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
   try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var sql=connection.createStatement();var row=sql.executeQuery("select g.prompt,g.prompt_version_id,g.experiment_id,s.kind,s.adapted_positive_prompt from generations g join rendered_prompt_snapshots s on s.id=g.prompt_snapshot_id")){
    assertThat(row.next()).isTrue();assertThat(row.getString("prompt")).isEqualTo("A legacy wolf");assertThat(row.getString("adapted_positive_prompt")).isEqualTo("A legacy wolf");assertThat(row.getString("kind")).isEqualTo("LEGACY");assertThat(row.getObject("prompt_version_id")).isNull();assertThat(row.getObject("experiment_id")).isNull();
   }
  }
 }
}
