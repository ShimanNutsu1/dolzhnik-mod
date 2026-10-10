package com.dolzhnik.debtmod.client;

import com.dolzhnik.debtmod.entity.CreditorEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

public class CreditorRenderer extends BipedEntityRenderer<CreditorEntity, BipedEntityModel<CreditorEntity>> {
    private static final Identifier TEXTURE = new Identifier("dolzhnik", "textures/entity/creditor.png");

    public CreditorRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.ZOMBIE)), 0.4f);
    }

    @Override
    public Identifier getTexture(CreditorEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void scale(CreditorEntity entity, MatrixStack matrices, float amount) {
        matrices.scale(0.92f, 1.3f, 0.92f); // чуть вытянутый, слишком высокий для человека
    }
}
