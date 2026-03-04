FROM apache/airflow:3.0.2

# providers만 추가 설치 (Airflow 자체는 base image에 포함)
RUN pip install --no-cache-dir \
    apache-airflow-providers-http==6.0.0
