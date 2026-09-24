package com.mediafactory;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class QualityMigrationIntegrationTest {
 @Test void upgradePreservesLegacyReviewsAndBackfillsEffectiveDecision()throws Exception{
  try(var pg=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))){pg.start();Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).target("4").load().migrate();
   UUID p=UUID.randomUUID(),c=UUID.randomUUID(),concept=UUID.randomUUID(),g=UUID.randomUUID(),a=UUID.randomUUID(),r=UUID.randomUUID();
   try(var connection=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var sql=connection.createStatement()){
    sql.execute("insert into projects(id,name) values('"+p+"','Legacy')");sql.execute("insert into collections(id,project_id,name) values('"+c+"','"+p+"','Legacy')");sql.execute("insert into concepts(id,collection_id,name,prompt) values('"+concept+"','"+c+"','Legacy','old prompt')");sql.execute("insert into generations(id,concept_id,status,prompt,width,height) values('"+g+"','"+concept+"','APPROVED','old prompt',128,128)");sql.execute("insert into assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height) values('"+a+"','"+g+"','legacy.png','"+"a".repeat(64)+"','image/png',100,128,128)");sql.execute("insert into quality_reviews(id,asset_id,kind,decision,reasons) values('"+r+"','"+a+"','HUMAN','APPROVED','Original operator evidence')");
   }
   Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().migrate();
   try(var connection=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var sql=connection.createStatement();var row=sql.executeQuery("select r.*,a.current_review_id from quality_reviews r join assets a on a.id=r.asset_id")){assertThat(row.next()).isTrue();assertThat(row.getString("reasons")).isEqualTo("Original operator evidence");assertThat(row.getString("final_decision")).isEqualTo("APPROVED");assertThat(row.getObject("generation_id")).isEqualTo(g);assertThat(row.getObject("current_review_id")).isEqualTo(r);assertThat(row.getObject("automatic_decision")).isNull();}
  }
 }
}
