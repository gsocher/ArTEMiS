package de.tum.in.www1.artemis.web.rest.dto;

/**
 * Created by Josias Montag on 26.09.16.
 */
public class ExerciseLtiConfigurationDTO {


    public ExerciseLtiConfigurationDTO() {
    }

    public ExerciseLtiConfigurationDTO(String launchUrl, String ltiId, String ltiPassport) {
        this.launchUrl = launchUrl;
        this.ltiId = ltiId;
        this.ltiPassport = ltiPassport;
    }


    private String launchUrl;
    private String ltiId;
    private String ltiPassport;

    public String getLaunchUrl() {
        return launchUrl;
    }

    public void setLaunchUrl(String launchUrl) {
        this.launchUrl = launchUrl;
    }

    public String getLtiId() {
        return ltiId;
    }

    public void setLtiId(String ltiId) {
        this.ltiId = ltiId;
    }

    public String getLtiPassport() {
        return ltiPassport;
    }

    public void setLtiPassport(String ltiPassport) {
        this.ltiPassport = ltiPassport;
    }
}
