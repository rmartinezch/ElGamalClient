set -euo pipefail
cd /home/verificatum/codex_mix_lab/sess-1583ac72
rm -f stub.xml privInfo.xml localProtInfo.xml protInfo01.xml protInfo.xml publicKey rand.txt pgroup.txt ciphertexts plaintexts plaintexts_orig ciphertextsout
PGROUP="$(vog -gen ECqPGroup -name P-256)"
printf '%s' "$PGROUP" > pgroup.txt
vmni -prot -sid 'ONPE' -name 'Eleccion Onpe' -nopart 1 -thres 1 -pgroup "$PGROUP" stub.xml
RAND="$(vog -gen RandomDevice /dev/urandom)"
printf '%s' "$RAND" > rand.txt
vmni -party -e -name 'Servidor01' -hint 'localhost:4041' -http 'http://localhost:8041' -rand "$RAND" stub.xml privInfo.xml localProtInfo.xml
cp localProtInfo.xml protInfo01.xml
vmni -merge protInfo01.xml protInfo.xml
vmn -keygen -e privInfo.xml protInfo.xml publicKey
wc -c publicKey
