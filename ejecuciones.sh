# 1) Inicia con el HOME correcto
sudo -u sonarqube -H bash -lc '~/sonarqube/bin/linux-x86-64/sonar.sh start'

#Community edition
sonar-scanner \
  -Dsonar.host.url=http://localhost:9100 \
  -Dsonar.token="sqa_9ed2cc7018cd4037a56b60aecbd31b750ac0df1b" \
  -Dsonar.projectKey=cifrador \
  -Dsonar.projectName="cifrador" \
  -Dsonar.java.binaries=target/classes

#SonarCloud
sonar-scanner \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.token="bbd725eb117bbea1112a42c4848dcb846282d46d" \
  -Dsonar.projectKey=onpetrial_cifrador \
  -Dsonar.organization=onpetrial \
  -Dsonar.projectName="cifrador" \
  -Dsonar.java.binaries=target/classes \
  -Dsonar.scm.disabled=true \
  -Dsonar.c.file.suffixes=- \
  -Dsonar.cpp.file.suffixes=- \
  -Dsonar.objc.file.suffixes=-