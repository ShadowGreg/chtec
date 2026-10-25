package ru.chtec.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Кто говорит: по словам автора («сказал Иван», «ответила она», «Иван сказал:») и по очерёдности
 * реплик в диалоге определяет персонажа каждой реплики и собирает список персонажей книги.
 */
public final class Roles {
    private Roles() {}

    /** Персонаж книги. */
    public static final class Person {
        public final String name;
        public int lines;
        int maleVotes, femaleVotes;

        Person(String name) {
            this.name = name;
        }

        /** 'm', 'f' или '?' */
        public char gender() {
            if (maleVotes > femaleVotes) return 'm';
            if (femaleVotes > maleVotes) return 'f';
            return guessByName(name);
        }
    }

    public static final String HERO = "Я (герой-рассказчик)";

    // Глаголы речи (мужской род прошедшего времени); женский род и возвратные формы проверяются по окончанию.
    private static final Set<String> SPEECH = new HashSet<>(Arrays.asList(
            "сказал", "ответил", "спросил", "крикнул", "воскликнул", "прошептал", "произнёс", "произнес", "заметил",
            "добавил", "продолжил", "возразил", "пробормотал", "буркнул", "усмехнулся", "улыбнулся", "засмеялся",
            "рассмеялся", "вздохнул", "позвал", "закричал", "прервал", "перебил", "повторил", "согласился",
            "поинтересовался", "объяснил", "предложил", "сообщил", "отозвался", "проговорил", "проворчал", "фыркнул",
            "хмыкнул", "подумал", "шепнул", "рявкнул", "выдохнул", "протянул", "молвил", "окликнул", "крикнул",
            "отрезал", "огрызнулся", "пояснил", "признался", "признал", "сказал", "уточнил", "подтвердил", "удивился",
            "обрадовался", "испугался", "вскрикнул", "простонал", "пропищал", "прохрипел", "процедил", "заорал",
            "завопил", "всхлипнул", "заговорил", "начал", "закончил", "откликнулся", "поправил", "подхватил",
            "передразнил", "пожаловался", "напомнил", "приказал", "попросил", "велел", "посоветовал", "предупредил",
            "кивнул", "отвечал", "говорил", "спрашивал", "кричал", "шептал", "твердил", "бормотал", "ворчал",
            "сказала", "вставил", "крякнул", "гаркнул", "пискнул", "выпалил", "изрёк", "изрек", "обратился",
            "сознался", "успокоил", "утешил", "похвалил", "пригласил", "поздоровался", "попрощался", "извинился"));

    // Персонажи, названные не по имени.
    private static final Set<String> ROLE_NOUNS = new HashSet<>(Arrays.asList(
            "старик", "старуха", "мальчик", "девочка", "мать", "отец", "дед", "дедушка", "бабушка", "бабка",
            "женщина", "мужчина", "незнакомец", "незнакомка", "капитан", "доктор", "врач", "учитель", "учительница",
            "солдат", "хозяин", "хозяйка", "девушка", "парень", "король", "королева", "царь", "царица", "князь",
            "княгиня", "граф", "графиня", "барин", "барыня", "мужик", "баба", "сын", "дочь", "брат", "сестра",
            "мама", "папа", "тётя", "тетя", "дядя", "старушка", "старичок", "гость", "кучер", "лакей", "слуга",
            "служанка", "офицер", "генерал", "полковник", "сторож", "продавец", "продавщица", "водитель", "шофёр",
            "шофер", "следователь", "инспектор", "сыщик", "профессор", "студент", "студентка", "ребёнок", "ребенок",
            "волк", "лиса", "медведь", "заяц", "кот", "пёс", "пес", "ведьма", "колдун", "принц", "принцесса"));

    private static final Set<String> NOT_NAMES = new HashSet<>(Arrays.asList(
            "Но", "И", "А", "Да", "Нет", "Что", "Как", "Так", "Это", "Вот", "Тут", "Там", "Ну", "Ах", "Ох", "Эх",
            "Потом", "Когда", "Если", "Тогда", "Теперь", "Вдруг", "Затем", "После", "Сейчас", "Только", "Даже",
            "Все", "Всё", "Кто", "Где", "Почему", "Зачем", "Однако", "Впрочем", "Наконец", "Сначала", "Тот", "Та",
            "Его", "Её", "Ее", "Им", "Ему", "Ей", "Мой", "Моя", "Наш", "Ваш", "Вы", "Ты", "Мы", "Они", "Господи",
            "Бог", "Боже", "Здравствуйте", "Спасибо", "Привет", "Пожалуйста", "Ладно", "Хорошо", "Глава"));

    private static final Pattern WORD = Pattern.compile("[А-ЯЁа-яё]+(?:-[А-ЯЁа-яё]+)?");

    private static boolean isVerb(String w) {
        String l = w.toLowerCase();
        if (SPEECH.contains(l)) return true;
        // женский род и возвратные формы глаголов речи: «сказала», «улыбнулась»
        if (l.endsWith("ла") && SPEECH.contains(l.substring(0, l.length() - 1))) return true;
        if (l.endsWith("лась") && SPEECH.contains(l.substring(0, l.length() - 3) + "ся")) return true;
        return l.equals("произнесла") || l.equals("изрекла");
    }

    /** Любой глагол прошедшего времени — для «Иван повернулся и сказал:» и для упоминаний в повествовании. */
    private static boolean isPastVerb(String w) {
        String l = w.toLowerCase();
        if (!w.equals(l) || l.length() < 4) return false;
        return isVerb(l) || l.matches(".*[аеиоуыяюё](л|ла|лся|лась)") || l.endsWith("ёл") || l.endsWith("шел");
    }

    private static char verbGender(String w) {
        String l = w.toLowerCase();
        if (l.endsWith("ла") || l.endsWith("лась")) return 'f';
        if (l.endsWith("л") || l.endsWith("лся") || l.endsWith("ёл") || l.endsWith("ел") || l.endsWith("ёк") || l.endsWith("ек")) return 'm';
        return '?';
    }

    private static boolean isName(String w) {
        return w.length() >= 2 && Character.isUpperCase(w.charAt(0)) && !NOT_NAMES.contains(w)
                && !w.equals("Он") && !w.equals("Она") && !w.equals("Я") && !w.toUpperCase().equals(w);
    }

    static char guessByName(String name) {
        String n = name.split(" ")[0].toLowerCase();
        if (n.startsWith("я (")) return '?';
        Set<String> maleA = new HashSet<>(Arrays.asList("никита", "илья", "кузьма", "фома", "лука", "саша", "миша",
                "гриша", "паша", "коля", "петя", "вася", "ваня", "дима", "серёжа", "сережа", "лёша", "леша", "витя",
                "федя", "женя", "толя", "юра", "слава", "дядя", "папа", "дедушка", "капитан"));
        if (maleA.contains(n)) return 'm';
        if (ROLE_NOUNS.contains(n)) {
            Set<String> fem = new HashSet<>(Arrays.asList("старуха", "девочка", "мать", "бабушка", "бабка", "женщина",
                    "незнакомка", "учительница", "хозяйка", "девушка", "королева", "царица", "княгиня", "графиня",
                    "барыня", "баба", "дочь", "сестра", "мама", "тётя", "тетя", "старушка", "служанка", "продавщица",
                    "студентка", "лиса", "ведьма", "принцесса"));
            return fem.contains(n) ? 'f' : 'm';
        }
        if (n.endsWith("а") || n.endsWith("я")) return 'f';
        return 'm';
    }

    /** Найденный в словах автора говорящий: имя (или «он»/«она»/«я») и пол по глаголу. */
    private static final class Found {
        String who;
        char gender;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Ищет говорящего рядом с глаголом: «сказал Иван», «Иван сказал», «ответила она», «сказал старик». */
    private static Found findSpeaker(String text, boolean anyVerb) {
        List<String> w = words(text);
        for (int k = 0; k < w.size(); k++) {
            String t = w.get(k);
            boolean verb = anyVerb ? isPastVerb(t) : isVerb(t);
            if (!verb) continue;
            char g = verbGender(t);
            // после глагола: до трёх слов («сказал тихо Иван», «ответила ему Маша»)
            for (int j = k + 1; j < Math.min(w.size(), k + 4); j++) {
                Found f = who(w, j, g);
                if (f != null) return f;
                if (isPastVerb(w.get(j))) break;
            }
            // перед глаголом: «Иван сказал», «Маша тихо сказала», «он ответил»
            for (int j = k - 1; j >= Math.max(0, k - 3); j--) {
                Found f = who(w, j, g);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static Found who(List<String> w, int j, char g) {
        String t = w.get(j);
        String l = t.toLowerCase();
        Found f = new Found();
        f.gender = g;
        if (l.equals("он")) {
            f.who = "он";
            if (f.gender == '?') f.gender = 'm';
            return f;
        }
        if (l.equals("она")) {
            f.who = "она";
            if (f.gender == '?') f.gender = 'f';
            return f;
        }
        if (l.equals("я")) {
            f.who = HERO;
            return f;
        }
        if (isName(t)) {
            String name = t;
            if (j + 1 < w.size() && isName(w.get(j + 1)) && !isPastVerb(w.get(j + 1))) name += " " + w.get(j + 1);
            f.who = name;
            return f;
        }
        if (ROLE_NOUNS.contains(l)) {
            f.who = Character.toUpperCase(l.charAt(0)) + l.substring(1);
            return f;
        }
        return null;
    }

    /**
     * Проставляет seg.speaker для реплик персонажей (у неопознанных остаётся null)
     * и возвращает персонажей в порядке убывания числа реплик.
     */
    public static Map<String, Person> attribute(List<Segment> segs) {
        Map<String, Person> cast = new LinkedHashMap<>();
        String lastMale = null, lastFemale = null;
        String prev = null, prevPrev = null;
        String intro = null;
        String addressee = null; // к кому обратились по имени в последней реплике («Иван, дело есть»)
        int gap = 0;
        int i = 0;
        while (i < segs.size()) {
            int b = segs.get(i).block;
            int j = i;
            boolean dialog = false;
            while (j < segs.size() && segs.get(j).block == b) {
                if (segs.get(j).role.equals("character")) dialog = true;
                j++;
            }
            List<Segment> para = segs.subList(i, j);
            i = j;

            if (!dialog) {
                StringBuilder sb = new StringBuilder();
                for (Segment s : para) sb.append(s.text).append(' ');
                String text = sb.toString().trim();
                // Упоминания в повествовании: «Маша вошла» — запоминаем пол и «последнего» персонажа.
                Found f = findSpeaker(text, true);
                String named = null;
                if (f != null && !f.who.equals("он") && !f.who.equals("она")) {
                    named = f.who;
                    Person p = person(cast, f.who);
                    if (f.gender == 'm') p.maleVotes++;
                    if (f.gender == 'f') p.femaleVotes++;
                    if (p.gender() == 'm') lastMale = p.name;
                    if (p.gender() == 'f') lastFemale = p.name;
                }
                // «Иван повернулся и сказал:» — следующая реплика его.
                intro = text.endsWith(":") && named != null ? named : null;
                if (++gap > 2) {
                    prev = null;
                    prevPrev = null;
                }
                continue;
            }
            gap = 0;

            String speaker = null;
            String remark = null;
            for (int pass = 0; pass < 2 && speaker == null; pass++) {
                // Сначала ищем глаголы речи («сказал»), потом любые («всплеснула руками Анна»).
                for (Segment s : para) {
                    if (!s.role.equals("narrator")) continue;
                    Found f = findSpeaker(s.text, pass == 1);
                    if (f == null) continue;
                    remark = s.text;
                if (f.who.equals("он")) speaker = lastMale;
                else if (f.who.equals("она")) speaker = lastFemale;
                else speaker = f.who;
                    if (speaker != null) {
                        Person p = person(cast, speaker);
                        if (f.gender == 'm') p.maleVotes++;
                        if (f.gender == 'f') p.femaleVotes++;
                    }
                    break;
                }
            }
            if (speaker == null && intro != null) speaker = intro;
            // Ответ тому, к кому обратились по имени.
            if (speaker == null && addressee != null && !addressee.equals(prev)) speaker = addressee;
            // Диалог двоих без подписей: реплики чередуются.
            if (speaker == null && prev != null && prevPrev != null && !prev.equals(prevPrev) && !prevPrev.equals("?")) {
                speaker = prevPrev;
            }
            intro = null;
            addressee = null;
            for (Segment s : para) {
                if (!s.role.equals("character")) continue;
                String a = addressee(s.text, cast, speaker);
                if (a != null) addressee = a;
            }
            // «спросил он меня», «сказала ей», «ответил ему» — следующая реплика того, к кому обратились.
            if (remark != null) {
                for (String w : words(remark.toLowerCase())) {
                    String a = null;
                    if (w.equals("меня") || w.equals("мне")) a = HERO;
                    else if (w.equals("её") || w.equals("ее") || w.equals("ей")) a = lastFemale;
                    else if (w.equals("его") || w.equals("ему")) a = lastMale;
                    if (a != null && !a.equals(speaker)) {
                        addressee = a;
                        break;
                    }
                }
            }

            if (speaker != null) {
                Person p = person(cast, speaker);
                for (Segment s : para) {
                    if (s.role.equals("character")) s.speaker = speaker;
                }
                p.lines++;
                if (p.gender() == 'm') lastMale = p.name;
                if (p.gender() == 'f') lastFemale = p.name;
            }
            prevPrev = prev;
            prev = speaker != null ? speaker : "?";
        }
        // Только те, у кого есть реплики; самые разговорчивые — первыми.
        List<Person> list = new ArrayList<>();
        for (Person p : cast.values()) if (p.lines > 0) list.add(p);
        java.util.Collections.sort(list, new java.util.Comparator<Person>() {
            @Override
            public int compare(Person a, Person b) {
                return b.lines - a.lines;
            }
        });
        Map<String, Person> out = new LinkedHashMap<>();
        for (Person p : list) out.put(p.name, p);
        return out;
    }

    private static final Pattern VOCATIVE = Pattern.compile("(?:^|[,.!?…]\\s)([А-ЯЁ][а-яё]+)(?=[,!?…])");

    /** Обращение по имени к известному персонажу: «Иван, дело есть», «Слушай, Маша!». */
    private static String addressee(String speech, Map<String, Person> cast, String speaker) {
        Matcher m = VOCATIVE.matcher(speech);
        while (m.find()) {
            String w = m.group(1);
            for (String name : cast.keySet()) {
                if (name.equals(speaker)) continue;
                if (name.equals(w) || name.startsWith(w + " ")) return name;
            }
        }
        return null;
    }

    private static Person person(Map<String, Person> cast, String name) {
        Person p = cast.get(name);
        if (p == null) {
            p = new Person(name);
            cast.put(name, p);
        }
        return p;
    }
}
