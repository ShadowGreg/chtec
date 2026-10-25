#!/usr/bin/env bash
# Проверки ядра приложения на компьютере (Java 17+, Python 3.9+).
#   ./tests/run.sh
# Сверка с Python-версией и токена Microsoft — если установлены зависимости desktop/requirements.txt;
# проверка клиента Microsoft на имитации сервера — если установлен пакет websockets.
set -u
cd "$(dirname "$0")/.."
export LC_ALL=C.UTF-8
OUT=$(mktemp -d)
J="java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp $OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" android/app/src/main/java/ru/chtec/core/*.java \
    tests/java/*.java tests/java/ru/chtec/core/*.java || exit 1
fail=0

echo "== Разбор текста: Java == Python"
if python3 -c "import docx, olefile" 2>/dev/null; then
    for f in examples/*; do
        if diff -q <(python3 desktop/chtec.py "$f" --score) <($J ScoreDump "$f") >/dev/null; then echo "  OK   $f"
        else echo "  FAIL $f"; fail=1; fi
    done
else
    echo "  пропущено: pip install -r desktop/requirements.txt"
fi

echo "== Привязка фраз и слов к тексту"
for f in examples/*; do $J AlignCheck "$f" | sed 's/ | оценка.*//; s/^/  /'; done

echo "== Роли: кто говорит"
for n in "" 2; do
    r=$($J RolesCheck tests/data/story$n.txt tests/data/expected$n.txt | grep "Реплик")
    echo "  story$n: $r"
    case "$r" in *"верно: "*) total=${r#*Реплик: }; total=${total%%,*}; got=${r##*верно: }; [ "$total" = "$got" ] || fail=1;; esac
done

echo "== Токен Microsoft: Java == edge-tts"
if python3 -c "import edge_tts" 2>/dev/null; then
    for t in 1761300000 1700000000.5 2000000000; do
        a=$($J ru.chtec.core.DrmCheck $t)
        b=$(python3 -c "from edge_tts.drm import generate_sec_ms_gec_v2 as g; print(g($t))")
        if [ "$a" = "$b" ]; then echo "  OK   $t"; else echo "  FAIL $t"; fail=1; fi
    done
else
    echo "  пропущено: pip install edge-tts"
fi

echo "== Клиент Microsoft на имитации сервера"
if python3 -c "import websockets, edge_tts" 2>/dev/null && command -v ffmpeg >/dev/null; then
    ffmpeg -loglevel error -y -f lavfi -i sine=f=440:r=24000:d=1 -ac 1 -c:a libmp3lame -b:a 48k \
        -write_xing 0 -id3v2_version 0 "$OUT/tone.mp3"
    python3 tests/mock_edge.py 18799 "$OUT/mock.log" "$OUT/tone.mp3" & srv=$!
    sleep 2
    if $J ru.chtec.core.EdgeCheck 18799 "$OUT/out.mp3" && cmp -s "$OUT/out.mp3" "$OUT/tone.mp3"; then echo "  OK"
    else echo "  FAIL"; fail=1; fi
    kill $srv 2>/dev/null
else
    echo "  пропущено: pip install websockets edge-tts и ffmpeg"
fi

rm -rf "$OUT"
[ $fail = 0 ] && echo "ВСЁ ХОРОШО" || { echo "ЕСТЬ ОШИБКИ"; exit 1; }
