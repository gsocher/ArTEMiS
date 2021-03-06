package de.tum.in.www1.artemis.service;

import de.tum.in.www1.artemis.domain.*;
import de.tum.in.www1.artemis.domain.enumeration.ParticipationState;
import de.tum.in.www1.artemis.domain.enumeration.SubmissionType;
import de.tum.in.www1.artemis.repository.ParticipationRepository;
import de.tum.in.www1.artemis.repository.ResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static de.tum.in.www1.artemis.domain.enumeration.ParticipationState.INITIALIZED;

/**
 * Service Implementation for managing Participation.
 */
@Service
@Transactional
public class ParticipationService {

    private final Logger log = LoggerFactory.getLogger(ParticipationService.class);

    private final ParticipationRepository participationRepository;
    private final ResultRepository resultRepository;
    private final QuizSubmissionService quizSubmissionService;
    private final UserService userService;
    private final Optional<GitService> gitService;
    private final Optional<ContinuousIntegrationService> continuousIntegrationService;
    private final Optional<VersionControlService> versionControlService;

    public ParticipationService(ParticipationRepository participationRepository,
                                ResultRepository resultRepository,
                                QuizSubmissionService quizSubmissionService,
                                UserService userService,
                                Optional<GitService> gitService,
                                Optional<ContinuousIntegrationService> continuousIntegrationService,
                                Optional<VersionControlService> versionControlService) {
        this.participationRepository = participationRepository;
        this.resultRepository = resultRepository;
        this.quizSubmissionService = quizSubmissionService;
        this.userService = userService;
        this.gitService = gitService;
        this.continuousIntegrationService = continuousIntegrationService;
        this.versionControlService = versionControlService;
    }

    /**
     * Save a participation.
     *
     * @param participation the entity to save
     * @return the persisted entity
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Participation save(Participation participation) {
        log.debug("Request to save Participation : {}", participation);
        return participationRepository.saveAndFlush(participation);
    }

    /**
     * This method should only be invoked for programming exercises, not for other exercises
     *
     * @param exercise
     * @param username
     * @return
     */
    @Transactional
    public Participation init(Exercise exercise, String username) {

        // common for all exercises
        // Check if participation already exists
        Participation participation = participationRepository.findOneByExerciseIdAndStudentLogin(exercise.getId(), username);
        if (!Optional.ofNullable(participation).isPresent() || (!(exercise instanceof QuizExercise) && participation.getInitializationState() == ParticipationState.FINISHED)) { //create a new participation only if it was finished before (not for quiz exercise)
            participation = new Participation();
            participation.setExercise(exercise);

            Optional<User> user = userService.getUserByLogin(username);
            if (user.isPresent()) {
                participation.setStudent(user.get());
            }
            participation = save(participation);
        }


        // specific to programming exercises
        if (exercise instanceof ProgrammingExercise) {
//            if (exercise.getCourse().isOnlineCourse()) {
//                participation.setLti(true);
//            } //TODO use in the future
            ProgrammingExercise programmingExercise = (ProgrammingExercise) exercise;
            participation.setInitializationState(ParticipationState.UNINITIALIZED);
            participation = copyRepository(participation, programmingExercise);
            participation = configureRepository(participation, programmingExercise);
            participation = copyBuildPlan(participation, programmingExercise);
            participation = configureBuildPlan(participation, programmingExercise);
            participation.setInitializationState(INITIALIZED);
            participation.setInitializationDate(ZonedDateTime.now());
        } else if (exercise instanceof QuizExercise) {
//            participation.setLti(false);    // QuizExercises do not support LTI at the moment
            if (participation.getInitializationState() == null) {
                participation.setInitializationState(INITIALIZED);
            }
            if (!Optional.ofNullable(participation.getInitializationDate()).isPresent()) {
                participation.setInitializationDate(ZonedDateTime.now());
            }
        }

        save(participation);
        return participation;
    }

    /**
     * Get a participation for the given quiz and username
     *
     * If the quiz hasn't ended, participation is constructed from cached submission
     *
     * If the quis has ended, we first look in the database for the participation
     * and construct one if none was found
     *
     * @param quizExercise the quiz exercise to attach to the participation
     * @param username the username of the user that the participation belongs to
     * @return the found or created participation
     */
    public Participation getParticipationForQuiz(QuizExercise quizExercise, String username) {
        if (quizExercise.isEnded()) {
            // try getting participation from database first
            Participation participation = findOneByExerciseIdAndStudentLoginAnyState(quizExercise.getId(), username);
            if (participation != null) {
                // add exercise
                participation.setExercise(quizExercise);

                // add result
                Result result = resultRepository.findFirstByParticipationIdAndRatedOrderByCompletionDateDesc(participation.getId(), true).orElse(null);

                participation.setResults(new HashSet<>());

                if (result != null) {
                    Submission submission = quizSubmissionService.findOne(result.getSubmission().getId());
                    result.setSubmission(submission);
                    participation.addResults(result);
                }

                return participation;
            }
        }

        // Look for Participation in ParticipationHashMap first
        Participation participation = QuizScheduleService.getParticipation(quizExercise.getId(), username);
        if (participation != null) {
            return participation;
        }

        // get submission from HashMap
        QuizSubmission quizSubmission = QuizScheduleService.getQuizSubmission(quizExercise.getId(), username);
        if (quizExercise.isEnded() && quizSubmission.getSubmissionDate() != null) {
            if (quizSubmission.isSubmitted()) {
                quizSubmission.setType(SubmissionType.MANUAL);
            } else {
                quizSubmission.setSubmitted(true);
                quizSubmission.setType(SubmissionType.TIMEOUT);
                quizSubmission.setSubmissionDate(ZonedDateTime.now());
            }
        }

        // construct result
        Result result = new Result().submission(quizSubmission);

        // construct participation
        participation = new Participation()
            .initializationState(INITIALIZED)
            .exercise(quizExercise)
            .addResults(result);

        if (quizExercise.isEnded() && quizSubmission.getSubmissionDate() != null) {
            // update result and participation state
            result.setRated(true);
            result.setCompletionDate(ZonedDateTime.now());
            participation.setInitializationState(ParticipationState.FINISHED);

            // calculate scores and update result and submission accordingly
            quizSubmission.calculateAndUpdateScores(quizExercise);
            result.evaluateSubmission();
        }

        return participation;
    }

    /**
     * Service method to resume inactive participation (with previously deleted build plan)
     *
     * @param exercise exercise to which the inactive participation belongs
     * @return resumed participation
     */
    public Participation resume(Exercise exercise, Participation participation) {
        ProgrammingExercise programmingExercise = (ProgrammingExercise) exercise;
        participation = copyBuildPlan(participation, programmingExercise);
        participation = configureBuildPlan(participation, programmingExercise);
        participation.setInitializationState(INITIALIZED);
        if (participation.getInitializationDate() == null) {
            //only set the date if it was not set before (which should NOT be the case)
            participation.setInitializationDate(ZonedDateTime.now());
        }
        save(participation);
        return participation;
    }

    private Participation copyRepository(Participation participation, ProgrammingExercise exercise) {
        if (!participation.getInitializationState().hasCompletedState(ParticipationState.REPO_COPIED)) {
            URL repositoryUrl = versionControlService.get().copyRepository(exercise.getBaseRepositoryUrlAsUrl(), participation.getStudent().getLogin());
            if (Optional.ofNullable(repositoryUrl).isPresent()) {
                participation.setRepositoryUrl(repositoryUrl.toString());
                participation.setInitializationState(ParticipationState.REPO_COPIED);
            }
            return save(participation);
        } else {
            return participation;
        }
    }

    private Participation configureRepository(Participation participation, ProgrammingExercise exercise) {
        if (!participation.getInitializationState().hasCompletedState(ParticipationState.REPO_CONFIGURED)) {
            versionControlService.get().configureRepository(participation.getRepositoryUrlAsUrl(), participation.getStudent().getLogin());
            participation.setInitializationState(ParticipationState.REPO_CONFIGURED);
            return save(participation);
        } else {
            return participation;
        }
    }

    private Participation copyBuildPlan(Participation participation, ProgrammingExercise exercise) {
        if (!participation.getInitializationState().hasCompletedState(ParticipationState.BUILD_PLAN_COPIED)) {
            String buildPlanId = continuousIntegrationService.get().copyBuildPlan(exercise.getBaseBuildPlanId(), participation.getStudent().getLogin());
            participation.setBuildPlanId(buildPlanId);
            participation.setInitializationState(ParticipationState.BUILD_PLAN_COPIED);
            return save(participation);
        } else {
            return participation;
        }
    }

    private Participation configureBuildPlan(Participation participation, ProgrammingExercise exercise) {
        if (!participation.getInitializationState().hasCompletedState(ParticipationState.BUILD_PLAN_CONFIGURED)) {
            continuousIntegrationService.get().configureBuildPlan(
                participation.getBuildPlanId(),
                participation.getRepositoryUrlAsUrl(),
                participation.getStudent().getLogin());
            participation.setInitializationState(ParticipationState.BUILD_PLAN_CONFIGURED);
            return save(participation);
        } else {
            return participation;
        }
    }

    /**
     * Get all the participations.
     *
     * @return the list of entities
     */
    @Transactional(readOnly = true)
    public List<Participation> findAll() {
        log.debug("Request to get all Participations");
        return participationRepository.findAll();
    }

    /**
     * Get all the participations.
     *
     * @param pageable the pagination information
     * @return the list of entities
     */
    @Transactional(readOnly = true)
    public Page<Participation> findAll(Pageable pageable) {
        log.debug("Request to get all Participations");
        return participationRepository.findAll(pageable);
    }

    /**
     * Get one participation by id.
     *
     * @param id the id of the entity
     * @return the entity
     */
    @Transactional(readOnly = true)
    public Participation findOne(Long id) {
        log.debug("Request to get Participation : {}", id);
        return participationRepository.findOne(id);
    }

    /**
     * Get one initialized/inactive participation by its student and exercise.
     *
     * @param exerciseId the project key of the exercise
     * @param username   the username of the student
     * @return the entity
     */
    @Transactional(readOnly = true)
    public Participation findOneByExerciseIdAndStudentLogin(Long exerciseId, String username) {
        log.debug("Request to get initialized/inactive Participation for User {} for Exercise with id: {}", username, exerciseId);

        Participation participation = participationRepository.findOneByExerciseIdAndStudentLoginAndInitializationState(exerciseId, username, INITIALIZED);
        if(!Optional.ofNullable(participation).isPresent()) {
            participation = participationRepository.findOneByExerciseIdAndStudentLoginAndInitializationState(exerciseId, username, ParticipationState.INACTIVE);
        }
        return participation;
    }

    /**
     * Get one participation (in any state) by its student and exercise.
     *
     * @param exerciseId the project key of the exercise
     * @param username   the username of the student
     * @return the entity
     */
    @Transactional(readOnly = true)
    public Participation findOneByExerciseIdAndStudentLoginAnyState(Long exerciseId, String username) {
        log.debug("Request to get Participation for User {} for Exercise with id: {}", username, exerciseId);

        Participation participation = participationRepository.findOneByExerciseIdAndStudentLogin(exerciseId, username);
        return participation;
    }

    /**
     * Get all participations for the given student including all results
     *
     * @param username the username of the student
     * @return the list of entities
     */
    @Transactional(readOnly = true)
    public List<Participation> findWithResultsByStudentUsername(String username) {
        return participationRepository.findByStudentUsernameWithEagerResults(username);
    }

    @Transactional(readOnly = true)
    public List<Participation> findByBuildPlanIdAndInitializationState(String buildPlanId, ParticipationState state) {
        log.debug("Request to get Participation for build plan id: {}", buildPlanId);
        return participationRepository.findByBuildPlanIdAndInitializationState(buildPlanId, state);
    }

    @Transactional(readOnly = true)
    public List<Participation> findByExerciseId(Long exerciseId) {
        return participationRepository.findByExerciseId(exerciseId);
    }

    @Transactional(readOnly = true)
    public List<Participation> findByExerciseIdWithEagerResults(Long exerciseId) {
        return participationRepository.findByExerciseIdWithEagerResults(exerciseId);
    }

    @Transactional(readOnly = true)
    public List<Participation> findByCourseId(Long courseId) {
        return participationRepository.findByCourseId(courseId);
    }

    /**
     * Delete the participation by id.
     *
     * @param id the id of the entity
     */
    @Transactional(noRollbackFor={Throwable.class})
    public void delete(Long id, boolean deleteBuildPlan, boolean deleteRepository) {
        log.debug("Request to delete Participation : {}", id);
        Participation participation = participationRepository.findOne(id);
        if (participation != null && participation.getExercise() instanceof ProgrammingExercise) {
            if (deleteBuildPlan && participation.getBuildPlanId() != null) {
                try {
                    continuousIntegrationService.get().deleteBuildPlan(participation.getBuildPlanId());
                }
                catch(Exception ex) {
                    log.error("Could not delete build plan: " + ex.getMessage());
                }
            }
            if (deleteRepository && participation.getRepositoryUrl() != null) {
                try {
                    versionControlService.get().deleteRepository(participation.getRepositoryUrlAsUrl());
                }
                catch(Exception ex) {
                    log.error("Could not delete repository: " + ex.getMessage());
                }
            }

            // delete local repository cache
            try {
                gitService.get().deleteLocalRepository(participation);
            } catch (Exception ex) {
                log.error("Error while deleting local repository", ex.getMessage());
            }
        }
        if (participation.getResults() != null && participation.getResults().size() > 0) {
            log.info("Will delete " + participation.getResults().size() + " results");
            for (Result result : participation.getResults()) {
                resultRepository.delete(result.getId());
            }
        }

        participationRepository.delete(id);
    }

    /**
     * Delete all participations belonging to the given exercise
     *
     * @param exerciseId the id of the exercise
     */
    @Transactional
    public void deleteAllByExerciseId(Long exerciseId) {
        List<Participation> participationsToDelete = participationRepository.findByExerciseId(exerciseId);

        for (Participation participation : participationsToDelete) {
            delete(participation.getId(), true, true);
        }
    }
}
