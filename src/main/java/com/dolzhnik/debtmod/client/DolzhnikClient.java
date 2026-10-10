package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.DebtMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class DolzhnikClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(DebtMod.CREDITOR, CreditorRenderer::new);
    }
}
