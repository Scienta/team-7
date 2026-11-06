package no.scienta.cfp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Speakers, talks and ratings.
 *
 * TODO split this up (CFP-140)
 */
@Service
public class ConferenceService {

    private static final Logger log = LoggerFactory.getLogger(ConferenceService.class);

    private static final Map<String, Integer> DURATIONS = new HashMap<>();

    static {
        DURATIONS.put("LIGHTNING_10", 10);
        DURATIONS.put("LIGHTNING_20", 20);
        DURATIONS.put("PRESENTATION", 45);
        DURATIONS.put("WORKSHOP", 120);
        // DURATIONS.put("KEYNOTE", 60);  // keynotes are invited, not submitted (2021)
    }

    private final SpeakerDao speakerDao;
    private final TalkDao talkDao;
    private final RatingDao ratingDao;
    private final CoSpeakerDao coSpeakerDao;
    private final NotificationService notifications;

    public ConferenceService(SpeakerDao speakerDao, TalkDao talkDao, RatingDao ratingDao, CoSpeakerDao coSpeakerDao,
                             NotificationService notifications) {
        this.speakerDao = speakerDao;
        this.talkDao = talkDao;
        this.ratingDao = ratingDao;
        this.coSpeakerDao = coSpeakerDao;
        this.notifications = notifications;
    }

    // ---------------------------------------------------------------- speakers

    @Transactional
    public Map<String, Object> registerSpeaker(SpeakerForm form) {
        if (!Utils.isValidEmail(form.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (speakerDao.emailTaken(form.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        long id;
        try {
            id = speakerDao.insert(form.getName(), form.getEmail(), form.getBio());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        log.info("registered speaker " + id + " (" + form.getEmail() + ")");
        notifications.speakerRegistered(form.getEmail(), form.getName());
        Map<String, Object> speaker = new LinkedHashMap<>();
        speaker.put("id", id);
        speaker.put("name", form.getName());
        speaker.put("email", form.getEmail());
        speaker.put("bio", form.getBio());
        return speaker;
    }

    public Map<String, Object> getSpeaker(long id) {
        Map<String, Object> speaker = speakerDao.findById(id);
        if (speaker == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        return speaker;
    }

    @Transactional
    public Map<String, Object> updateSpeaker(long id, String name, String email, String bio) {
        Map<String, Object> existing = getSpeaker(id);
        if (name == null || name.equals("")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (!Utils.isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (bio != null && bio.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bio too long");
        }
        if (!email.equalsIgnoreCase((String) existing.get("email")) && speakerDao.emailTaken(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        speakerDao.update(id, name, email, bio);
        return getSpeaker(id);
    }

    public List<Map<String, Object>> searchSpeakers(String q) {
        return speakerDao.search(q);
    }

    // ---------------------------------------------------------------- talks

    @Transactional
    public TalkDto submitTalk(TalkForm form) {
        if (form.title == null || form.title.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        if (form.format == null || !DURATIONS.containsKey(form.format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be one of " + DURATIONS.keySet());
        }
        if (form.level == null || !Arrays.asList("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(form.level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown level");
        }
        if (form.speakerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId is required");
        }
        if (!speakerDao.exists(form.speakerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speaker " + form.speakerId + " does not exist");
        }

        int duration;
        switch (form.format) {
            case "LIGHTNING_10":
                duration = 10;
                break;
            case "LIGHTNING_20":
                duration = 20;
                break;
            case "WORKSHOP":
                duration = 120;
                break;
            default:
                duration = 45;
        }

        Long id = checkSubmissionLimit(form, duration);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a speaker can submit at most 3 talks");
        }
        Map<String, Object> speaker = speakerDao.findById(form.speakerId);
        notifications.talkSubmitted((String) speaker.get("email"), form.title);
        return getTalk(id);
    }

    /**
     * Checks that the speaker is below the limit of 3 talks.
     */
    private Long checkSubmissionLimit(TalkForm form, int duration) {
        return talkDao.insertIfBelow(3, form.title, form.format, duration, form.level, form.speakerId,
                System.currentTimeMillis());
    }

    public TalkDto getTalk(long id) {
        TalkDto talk = talkDao.findById(id);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        talk.coSpeakerIds = coSpeakerDao.findByTalk(talk.id);
        return talk;
    }

    public List<TalkDto> getTalksForSpeaker(long speakerId) {
        if (speakerDao.findById(speakerId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        List<TalkDto> talks = talkDao.findBySpeaker(speakerId);
        addCoSpeakers(talks);
        return talks;
    }

    public List<TalkDto> listTalks(String sort, Long speakerId) {
        if (sort != null && !sort.equals("rating")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be 'rating'");
        }
        List<TalkDto> talks = speakerId == null ? talkDao.findAll() : talkDao.findBySpeaker(speakerId);
        addCoSpeakers(talks);
        if ("rating".equals(sort)) {
            List<TalkDto> rated = new ArrayList<>();
            List<TalkDto> unrated = new ArrayList<>();
            for (TalkDto t : talks) {
                if (t.ratingCount > 0) {
                    rated.add(t);
                } else {
                    unrated.add(t);
                }
            }
            rated.sort((a, b) -> {
                int c = Double.compare(b.averageRating, a.averageRating);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(b.ratingCount, a.ratingCount);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.id, b.id);
            });
            rated.addAll(unrated);
            return rated;
        }
        return talks;
    }

    // ---------------------------------------------------------------- acceptance

    // For each status a talk can move to, the statuses it may come from. See specs/acceptance.md.
    private static final Map<String, List<String>> ALLOWED_FROM = new LinkedHashMap<>();

    static {
        ALLOWED_FROM.put("ACCEPTED", Arrays.asList("SUBMITTED", "WAITLISTED"));
        ALLOWED_FROM.put("REJECTED", Arrays.asList("SUBMITTED", "WAITLISTED"));
        ALLOWED_FROM.put("WAITLISTED", Arrays.asList("SUBMITTED"));
        ALLOWED_FROM.put("WITHDRAWN", Arrays.asList("SUBMITTED", "WAITLISTED", "ACCEPTED"));
    }

    private static final int MIN_REVIEWERS_TO_ACCEPT = 2;

    @Transactional
    public TalkDto decide(long talkId, Map<String, Object> body) {
        Object status = body == null ? null : body.get("status");
        if (!("ACCEPTED".equals(status) || "REJECTED".equals(status) || "WAITLISTED".equals(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be ACCEPTED, REJECTED or WAITLISTED");
        }
        String to = (String) status;
        int minReviewers = to.equals("ACCEPTED") ? MIN_REVIEWERS_TO_ACCEPT : 0;
        if (!talkDao.updateStatusIf(talkId, to, ALLOWED_FROM.get(to), minReviewers, null)) {
            TalkDto talk = getTalk(talkId);  // 404 if it doesn't exist
            if (!ALLOWED_FROM.get(to).contains(talk.status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + talk.status + " talk can't become " + to);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a talk needs ratings from at least " + MIN_REVIEWERS_TO_ACCEPT + " reviewers to be accepted");
        }
        log.info("talk " + talkId + " -> " + to);
        return getTalk(talkId);
    }

    @Transactional
    public TalkDto withdraw(long talkId, Map<String, Object> body) {
        Object speaker = body == null ? null : body.get("speakerId");
        if (!(speaker instanceof Integer || speaker instanceof Long)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId must be a whole number");
        }
        long speakerId = ((Number) speaker).longValue();
        TalkDto talk = getTalk(talkId);  // 404 if it doesn't exist
        if (talk.speakerId != speakerId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the talk's speaker can withdraw it");
        }
        if (!talkDao.updateStatusIf(talkId, "WITHDRAWN", ALLOWED_FROM.get("WITHDRAWN"), 0, speakerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a " + getTalk(talkId).status + " talk can't be withdrawn");
        }
        log.info("talk " + talkId + " withdrawn by speaker " + speakerId);
        return getTalk(talkId);
    }

    private void addCoSpeakers(List<TalkDto> talks) {
        List<Long> ids = new ArrayList<>();
        for (TalkDto t : talks) {
            ids.add(t.id);
        }
        Map<Long, List<Long>> coSpeakers = coSpeakerDao.findByTalks(ids);
        for (TalkDto t : talks) {
            t.coSpeakerIds = coSpeakers.getOrDefault(t.id, new ArrayList<>());
        }
    }

    // ---------------------------------------------------------------- co-speakers

    static final int MAX_CO_SPEAKERS = 2;

    /**
     * Adds a registered speaker as a co-speaker. Adding someone who already is one changes nothing (200, also when
     * the talk is full). The talk's row is locked first, so concurrent additions can't exceed the limit.
     */
    @Transactional
    public TalkDto addCoSpeaker(long talkId, long speakerId) {
        long mainSpeaker = lockTalk(talkId);
        requireSpeaker(speakerId);
        if (mainSpeaker == speakerId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the main speaker can't also be a co-speaker of their own talk");
        }
        List<Long> current = coSpeakerDao.findByTalk(talkId);
        if (!current.contains(speakerId)) {
            if (current.size() >= MAX_CO_SPEAKERS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a talk has at most " + MAX_CO_SPEAKERS + " co-speakers");
            }
            coSpeakerDao.add(talkId, speakerId);
        }
        return getTalk(talkId);
    }

    /**
     * Removes a co-speaker. Removing a registered speaker who isn't one changes nothing (200).
     */
    @Transactional
    public TalkDto removeCoSpeaker(long talkId, long speakerId) {
        lockTalk(talkId);
        requireSpeaker(speakerId);
        coSpeakerDao.remove(talkId, speakerId);
        return getTalk(talkId);
    }

    private long lockTalk(long talkId) {
        Long mainSpeaker = talkDao.lockAndGetSpeaker(talkId);
        if (mainSpeaker == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        return mainSpeaker;
    }

    private void requireSpeaker(long speakerId) {
        if (!speakerDao.exists(speakerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
    }

    // ---------------------------------------------------------------- statistics

    public Map<String, Object> statistics() {
        List<TalkDto> talks = listTalks(null, null);
        Map<String, Integer> byFormat = new LinkedHashMap<>();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<Long> speakersWithTalks = new ArrayList<>();
        int minutes = 0;
        double ratingSum = 0;
        int rated = 0;
        for (TalkDto t : talks) {
            byFormat.put(t.format, byFormat.getOrDefault(t.format, 0) + 1);
            byLevel.put(t.level, byLevel.getOrDefault(t.level, 0) + 1);
            byStatus.put(t.status, byStatus.getOrDefault(t.status, 0) + 1);
            if (!speakersWithTalks.contains(t.speakerId) && speakerDao.findById(t.speakerId) != null) {
                speakersWithTalks.add(t.speakerId);
            }
            minutes += t.durationMinutes;
            if (t.averageRating != null) {
                ratingSum += t.averageRating;
                rated++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("speakers", speakerDao.count());
        stats.put("speakersWithTalks", speakersWithTalks.size());
        stats.put("talks", talks.size());
        stats.put("byFormat", byFormat);
        stats.put("byLevel", byLevel);
        stats.put("byStatus", byStatus);
        stats.put("totalHours", minutes / 60);
        stats.put("averageRating", rated == 0 ? null : Utils.roundRating(ratingSum / rated));
        stats.put("generated", Utils.now());
        return stats;
    }

    // ---------------------------------------------------------------- ratings

    @Transactional
    public Map<String, Object> rate(long talkId, long reviewerId, Map<String, Object> body) {
        if (reviewerId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reviewerId must be positive");
        }
        Object score = body.get("score");
        if (!(score instanceof Integer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be a whole number");
        }
        int s = (Integer) score;
        if (s < 1 || s > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be 1-5");
        }
        if (!talkDao.exists(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        String now = Utils.now();
        ratingDao.save(talkId, reviewerId, s, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("talkId", talkId);
        result.put("reviewerId", reviewerId);
        result.put("score", s);
        result.put("ratedAtDisplay", Utils.usShort(Utils.parse(now)));
        return result;
    }
}
