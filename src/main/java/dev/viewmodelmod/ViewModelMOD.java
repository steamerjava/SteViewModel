package dev.viewmodelmod;

import dev.viewmodelmod.editor.ViewModelEditorOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only entry point. The mod never touches server logic. */
public final class ViewModelMOD implements ClientModInitializer {
    public static final String MOD_ID = "viewmodelmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ViewModelManager manager;

    @Override
    public void onInitializeClient() {
        manager = new ViewModelManager();
        manager.load();

        ViewModelEditorOverlay.registerEvents();
        ClientTickEvents.END_CLIENT_TICK.register(client -> manager.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> manager.saveNow());
    }

    public static ViewModelManager getManager() {
        return manager;
    }
}
