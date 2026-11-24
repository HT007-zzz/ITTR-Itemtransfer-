package com.kuanian.itemtransfer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(ItemTransferMod.MODID)
public class ItemTransferMod {
    public static final String MODID = "itemtransfer";

    public ItemTransferMod() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                TransferCommand.register(event.getDispatcher()));
    }
}
