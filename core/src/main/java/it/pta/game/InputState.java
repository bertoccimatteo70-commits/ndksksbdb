package it.pta.game;

/** Per-frame input, written by the touch HUD / hardware keys and consumed by World. */
public final class InputState {
    public float moveX, moveY;
    public boolean run, jump, attack;
    public boolean throttle, brake, steerLeft, steerRight, handbrake;
    public boolean enterExit, interact, switchView, switchCharacter, pause, phone, map;
    public float cameraDX, cameraDY;
    public float steerAxis;

    public void endFrame() {
        jump = false;
        attack = false;
        enterExit = false;
        interact = false;
        switchView = false;
        switchCharacter = false;
        pause = false;
        phone = false;
        map = false;
        cameraDX = 0;
        cameraDY = 0;
    }

    public float throttleAxis() {
        return (throttle ? 1f : 0f) - (brake ? 1f : 0f);
    }

    public float steering() {
        if (steerAxis != 0) {
            return steerAxis;
        }
        return (steerRight ? 1f : 0f) - (steerLeft ? 1f : 0f);
    }

    public void reset() {
        moveX = 0;
        moveY = 0;
        run = false;
        jump = false;
        attack = false;
        throttle = false;
        brake = false;
        steerLeft = false;
        steerRight = false;
        handbrake = false;
        enterExit = false;
        interact = false;
        switchView = false;
        switchCharacter = false;
        pause = false;
        phone = false;
        map = false;
        steerAxis = 0;
        cameraDX = 0;
        cameraDY = 0;
    }
}
