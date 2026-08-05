package net.Dispatcher;

import com.simibubi.create.foundation.data.CreateRegistrate;
import net.Dispatcher.foundation.util.AllDispatcherItems;
import net.Dispatcher.foundation.util.AllMenuTypes;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DispatcherMod {
    public static final String MOD_ID = "createdispatcher";
    public static final String NAME = "Create Dispatcher";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public static void init() {
    }

    public static void commonSetup() {
        DNetworking.register();
        AllDispatcherItems.register();
        AllMenuTypes.register();
        net.Dispatcher.web.WebBootstrap.init();
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
