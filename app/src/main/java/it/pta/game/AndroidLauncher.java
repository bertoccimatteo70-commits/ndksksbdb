package it.pta.game;

import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

/** Android entry point for PTA. */
public final class AndroidLauncher extends AndroidApplication {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useImmersiveMode = true;
        config.numSamples = 0;
        config.useWakelock = true;
        PtaGame game = new PtaGame();
        // CI hook: `adb shell am start ... --ez pta.smoke true` boots straight into a
        // new game and walks around, so the pipeline exercises the real game loop.
        game.setSmokeTest(getIntent() != null
                && getIntent().getBooleanExtra("pta.smoke", false));
        initialize(game, config);
    }
}
