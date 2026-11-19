# NEVE 1081 — эквалайзер и преамп (VST3, Windows)
Преамп, ФВЧ, параллельное «Тепло», 4-полосный EQ (НЧ shelf, Н-Ср, В-Ср, ВЧ shelf с регулировкой частот), выходной уровень, мягкий лимитер.
Сборка: загрузите файлы в репозиторий GitHub → Actions → Build VST3 (Windows) → скачайте артефакт NEVE1081-VST3 (или build_windows.bat).
Установка: папку `NEVE 1081.vst3` в `C:\Program Files\Common Files\VST3\`, затем Find plugins в FL Studio. Без задержки (0 мс). Интерфейс — неоновые ручки, как в веб-версии.

## Самый простой способ (GitHub)
1. github.com → New repository → создайте пустой (Public).
2. «uploading an existing file» → перетащите ВЕСЬ распакованный архив (папку Source, CMakeLists.txt, build.yml.txt и т.д.).
3. Если вкладка Actions пуста: Add file → Create new file → имя `.github/workflows/build.yml` → вставьте содержимое `build.yml.txt` → Commit.
4. Actions → последний запуск → внизу Artifacts → `NEVE1081-VST3`.
