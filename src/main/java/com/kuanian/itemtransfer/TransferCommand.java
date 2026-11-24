package com.kuanian.itemtransfer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class TransferCommand {
    private static final String EXT = ".nbt";

    private TransferCommand() {}

    private static Path dir() {
        return FMLPaths.GAMEDIR.get().resolve("item_transfer");
    }

    private static final SuggestionProvider<CommandSourceStack> FILE_SUGGESTIONS = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        for (Path p : listFiles()) {
            String n = p.getFileName().toString();
            names.add(n.substring(0, n.length() - EXT.length()));
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("itemtransfer")
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("export")
                        .executes(ctx -> export(ctx.getSource(), null, true))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> export(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true))))
                .then(Commands.literal("copy")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> export(ctx.getSource(), null, false))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> export(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))))
                .then(Commands.literal("import")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests(FILE_SUGGESTIONS)
                                .executes(ctx -> importFile(ctx.getSource(), StringArgumentType.getString(ctx, "file")))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource())));

        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
        dispatcher.register(Commands.literal("itx").executes(ctx -> help(ctx.getSource())).redirect(node));
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal(
                "§e/itemtransfer export [名字]§r 导出主手物品（会从手里拿走）\n"
                        + "§e/itemtransfer import <文件名>§r 导入物品（文件会移到 imported 子文件夹）\n"
                        + "§e/itemtransfer list§r 查看可导入的文件\n"
                        + "文件夹: " + dir().toAbsolutePath()), false);
        return 1;
    }

    private static String sanitize(String s) {
        String r = s.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^\\.+", "_");
        if (r.toLowerCase(Locale.ROOT).endsWith(EXT)) {
            r = r.substring(0, r.length() - EXT.length());
        }
        return r.trim();
    }

    private static Path uniquePath(Path folder, String base) {
        Path p = folder.resolve(base + EXT);
        int i = 2;
        while (Files.exists(p)) {
            p = folder.resolve(base + "_" + i + EXT);
            i++;
        }
        return p;
    }

    private static int export(CommandSourceStack src, String name, boolean remove) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            src.sendFailure(Component.literal("主手是空的，请先把要导出的物品拿在主手上。"));
            return 0;
        }

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String base = name == null ? "" : sanitize(name);
        if (base.isEmpty()) {
            String idPart = id == null ? "item" : id.toString().replace(':', '_');
            base = idPart + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("Item", stack.save(new CompoundTag()));
        root.putString("FromWorld", src.getServer().getWorldData().getLevelName());
        root.putString("FromPlayer", player.getGameProfile().getName());

        try {
            Path folder = dir();
            Files.createDirectories(folder);
            Path target = uniquePath(folder, base);
            NbtIo.writeCompressed(root, target.toFile());

            String fileName = target.getFileName().toString();
            String desc = stack.getCount() + " × " + stack.getHoverName().getString();
            if (remove) {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            src.sendSuccess(() -> Component.literal("已导出 " + desc + " → " + fileName
                    + (remove ? "（已从手里拿走）" : "（物品保留）")), false);
            return 1;
        } catch (IOException | InvalidPathException e) {
            src.sendFailure(Component.literal("导出失败: " + e));
            return 0;
        }
    }

    private static List<Path> listFiles() {
        List<Path> out = new ArrayList<>();
        Path folder = dir();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (Stream<Path> s = Files.list(folder)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXT))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static int list(CommandSourceStack src) {
        List<Path> files = listFiles();
        if (files.isEmpty()) {
            src.sendSuccess(() -> Component.literal("没有可导入的文件。请把 .nbt 文件放进: " + dir().toAbsolutePath()), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("可导入的文件（" + files.size() + "）:");
        for (Path p : files) {
            String n = p.getFileName().toString();
            sb.append("\n- ").append(n.substring(0, n.length() - EXT.length()));
            try {
                CompoundTag root = NbtIo.readCompressed(p.toFile());
                CompoundTag item = root.getCompound("Item");
                sb.append("  (").append(item.getString("id")).append(" ×").append(item.getByte("Count"));
                if (root.contains("FromWorld")) {
                    sb.append("，来自 ").append(root.getString("FromWorld"));
                }
                sb.append(")");
            } catch (IOException | RuntimeException e) {
                sb.append("  (无法读取)");
            }
        }
        String text = sb.toString();
        src.sendSuccess(() -> Component.literal(text), false);
        return files.size();
    }

    private static Path resolveImport(String input) {
        try {
            String n = input.trim();
            if (!n.toLowerCase(Locale.ROOT).endsWith(EXT)) {
                n += EXT;
            }
            Path folder = dir().toAbsolutePath().normalize();
            Path p = folder.resolve(n).normalize();
            if (p.getParent() == null || !p.getParent().equals(folder) || !Files.isRegularFile(p)) {
                return null;
            }
            return p;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static int importFile(CommandSourceStack src, String fileName) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Path file = resolveImport(fileName);
        if (file == null) {
            src.sendFailure(Component.literal("找不到文件: " + fileName + "。文件夹: " + dir().toAbsolutePath()));
            return 0;
        }

        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file.toFile());
        } catch (IOException | RuntimeException e) {
            src.sendFailure(Component.literal("文件读取失败: " + e));
            return 0;
        }

        int fileVersion = root.getInt("DataVersion");
        int currentVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (fileVersion > currentVersion) {
            src.sendFailure(Component.literal("这个文件来自更新版本的游戏，无法导入。"));
            return 0;
        }

        CompoundTag itemTag = root.getCompound("Item");
        ItemStack stack;
        try {
            stack = ItemStack.of(itemTag);
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal("物品数据损坏: " + e));
            return 0;
        }
        if (stack.isEmpty()) {
            src.sendFailure(Component.literal("无法导入：物品 " + itemTag.getString("id")
                    + " 在当前存档不存在（可能没有安装对应的模组）。文件未被移动。"));
            return 0;
        }

        try {
            Path done = dir().resolve("imported");
            Files.createDirectories(done);
            String n = file.getFileName().toString();
            Files.move(file, uniquePath(done, n.substring(0, n.length() - EXT.length())), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            src.sendFailure(Component.literal("无法移动已导入的文件，已取消导入: " + e));
            return 0;
        }

        ItemStack give = stack.copy();
        player.getInventory().add(give);
        if (!give.isEmpty()) {
            ItemEntity entity = player.drop(give, false);
            if (entity != null) {
                entity.setNoPickUpDelay();
            }
        }

        String desc = stack.getCount() + " × " + stack.getHoverName().getString();
        src.sendSuccess(() -> Component.literal("已导入 " + desc + "（背包满时会掉在脚下）"), false);
        return 1;
    }
                                  }
