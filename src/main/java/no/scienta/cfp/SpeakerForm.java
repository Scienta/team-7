package no.scienta.cfp;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /speakers. The bio length is checked in ConferenceService: @Size counts UTF-16 units, so
 * 500 emoji were too long.
 */
public class SpeakerForm {

    @NotBlank
    private String name;

    @NotBlank
    private String email;

    private String bio;

    // TODO twitter handle, see CFP-112
    // private String twitter;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }
}
