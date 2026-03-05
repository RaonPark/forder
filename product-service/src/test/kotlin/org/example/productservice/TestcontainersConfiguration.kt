package org.example.productservice

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun mongoDbContainer(): MongoDBContainer {
        /**
         * MongoDBContainer(testcontainers 1.19+)는 내부적으로:
         *   1. configure()  → --replSet docker-rs 커맨드 설정
         *   2. containerIsStarted() → rs.initiate({_id: "docker-rs", ...}) 실행
         * 즉, 이미 단일 노드 레플리카셋(docker-rs)으로 초기화된 상태로 올라온다.
         *
         * 문제: @ServiceConnection이 제공하는 기본 URI는
         *   mongodb://host:port/?directConnection=true
         * MongoDB Java Driver는 directConnection=true 만으로는 레플리카셋 토폴로지를 인식하지 않아
         * @Transactional(MongoDB 트랜잭션)과 retryWrites 가 실패할 수 있다.
         *
         * 해결: getConnectionString()을 재정의해 replicaSet=docker-rs 를 명시한다.
         *   - directConnection=true : 컨테이너 내부 호스트명 재접속 차단 (외부에서 접근 불가)
         *   - replicaSet=docker-rs  : 드라이버가 레플리카셋 모드 진입 → 트랜잭션·retryWrites 활성화
         *
         * start() 재정의는 하지 않는다.
         * MongoDBContainer.configure()가 내부에서 --replSet docker-rs를 설정하므로,
         * 별도 withCommand()로 다른 replSet 이름을 지정하면 충돌이 발생한다.
         */
        return object : MongoDBContainer(DockerImageName.parse("mongo:latest")) {
            override fun getConnectionString(): String =
                "mongodb://$host:${getMappedPort(27017)}/?replicaSet=docker-rs&directConnection=true"
        }
    }

}
