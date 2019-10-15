package io.anuke.mindustry.game;

import io.anuke.arc.*;
import io.anuke.arc.collection.*;
import io.anuke.arc.files.*;
import io.anuke.arc.graphics.*;
import io.anuke.arc.graphics.g2d.*;
import io.anuke.arc.graphics.glutils.*;
import io.anuke.arc.util.*;
import io.anuke.arc.util.io.Streams.*;
import io.anuke.arc.util.serialization.*;
import io.anuke.mindustry.*;
import io.anuke.mindustry.content.*;
import io.anuke.mindustry.entities.traits.BuilderTrait.*;
import io.anuke.mindustry.game.EventType.*;
import io.anuke.mindustry.game.Schematic.*;
import io.anuke.mindustry.type.*;
import io.anuke.mindustry.world.*;

import java.io.*;
import java.util.zip.*;

import static io.anuke.mindustry.Vars.*;

/** Handles schematics.*/
public class Schematics{
    private static final byte[] header = {'m', 's', 'c', 'h'};
    private static final byte version = 0;

    private static final int resolution = 64;
    private static final int padding = 2;
    private static final int maxSize = 64;

    private OptimizedByteArrayOutputStream out = new OptimizedByteArrayOutputStream(1024);
    private Array<Schematic> all = new Array<>();
    private OrderedMap<Schematic, FrameBuffer> previews = new OrderedMap<>();
    private FrameBuffer shadowBuffer;

    public Schematics(){
        Events.on(DisposeEvent.class, e -> {
            previews.each((schem, buffer) -> buffer.dispose());
            previews.clear();
            shadowBuffer.dispose();
        });
    }

    /** Load all schematics in the folder immediately.*/
    public void load(){
        all.clear();
        for(FileHandle file : schematicDirectory.list()){
            if(!file.extension().equals(schematicExtension)) continue;

            try{
                all.add(read(file));
            }catch(IOException e){
                e.printStackTrace();
            }
        }

        Core.app.post(() -> {
            shadowBuffer = new FrameBuffer(maxSize + padding, maxSize + padding);
        });
    }

    public Texture getPreview(Schematic schematic){
        if(!previews.containsKey(schematic)){
            Draw.blend();
            Draw.color();
            Time.mark();
            FrameBuffer buffer = new FrameBuffer((schematic.width + padding) * resolution, (schematic.height + padding) * resolution);
            Tmp.m1.set(Draw.proj());

            shadowBuffer.beginDraw(Color.clear);

            Draw.proj().setOrtho(0, 0, shadowBuffer.getWidth(), shadowBuffer.getHeight());

            Draw.color();
            schematic.tiles.each(t -> {
                int size = t.block.size;
                int offsetx = -(size - 1) / 2;
                int offsety = -(size - 1) / 2;
                for(int dx = 0; dx < size; dx++){
                    for(int dy = 0; dy < size; dy++){
                        int wx = t.x + dx + offsetx;
                        int wy = t.y + dy + offsety;
                        Fill.square(padding/2f + wx + 0.5f, padding/2f + wy + 0.5f, 0.5f);
                    }
                }
            });

            shadowBuffer.endDraw();

            buffer.beginDraw(Color.orange);

            Draw.proj().setOrtho(0, 0, buffer.getWidth(), buffer.getHeight());
            for(int x = 0; x < schematic.width + padding; x++){
                for(int y = 0; y < schematic.height + padding; y++){
                    Draw.rect("dark-panel-4", x * resolution + resolution/2f, y * resolution + resolution/2f, resolution, resolution);
                }
            }

            Tmp.tr1.set(shadowBuffer.getTexture(), 0, 0, schematic.width + padding, schematic.height + padding);
            Draw.color(0f, 0f, 0f, 1f);
            Draw.rect(Tmp.tr1, buffer.getWidth()/2f, buffer.getHeight()/2f, buffer.getWidth(), buffer.getHeight());
            Draw.color();

            schematic.tiles.each(t -> {
                float offset = (t.block.size + 1) % 2 / 2f;
                Draw.rect(t.block.icon(Cicon.full),
                    (t.x + 0.5f + padding/2f + offset) * resolution,
                    buffer.getHeight() - 1 - (t.y + 0.5f + padding/2f + offset) * resolution,
                    resolution * t.block.size, -resolution * t.block.size, t.block.rotate ? t.rotation * 90 : 0);
            });

            buffer.endDraw();

            Draw.proj(Tmp.m3);

            previews.put(schematic, buffer);
            Log.info("Time taken: {0}", Time.elapsed());
        }

        return previews.get(schematic).getTexture();
    }

    /** Creates an array of build requests from a schematic's data, centered on the provided x+y coordinates. */
    public Array<BuildRequest> toRequests(Schematic schem, int x, int y){
        return schem.tiles.map(t -> new BuildRequest(t.x + x - schem.width/2, t.y + y - schem.height/2, t.rotation, t.block).configure(t.config));
    }

    /** Creates a schematic from a world selection. */
    public Schematic create(int x, int y, int x2, int y2){
        if(x > x2){
            int temp = x;
            x = x2;
            x2 = temp;
        }

        if(y > y2){
            int temp = y;
            y = y2;
            y2 = temp;
        }

        Array<Stile> tiles = new Array<>();

        int width = x2 - x + 1, height = y2 - y + 1;
        int offsetX = -x, offsetY = -y;
        for(int cx = x; cx <= x2; cx++){
            for(int cy = y; cy <= y2; cy++){
                Tile tile = world.tile(cx, cy);

                if(tile != null && tile.entity != null){
                    int config = tile.entity.config();
                    if(tile.entity.posConfig()){
                        config = Pos.get(Pos.x(config) + offsetX, Pos.y(config) + offsetY);
                    }

                    tiles.add(new Stile(tile.block(), cx + offsetX, cy + offsetY, config, tile.rotation()));
                }
            }
        }

        return new Schematic(tiles, width, height);
    }

    /** Converts a schematic to base64. */
    public String writeBase64(Schematic schematic){
        try{
            out.reset();
            write(schematic, out);
            return new String(Base64Coder.encode(out.getBuffer(), out.size()));
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    /** Loads a schematic from base64. May throw an exception. */
    public Schematic readBase64(String schematic) throws IOException{
        return read(new ByteArrayInputStream(Base64Coder.decode(schematic)));
    }

    //region IO methods

    public static Schematic read(FileHandle file) throws IOException{
        return read(new DataInputStream(file.read(1024)));
    }

    public static Schematic read(InputStream input) throws IOException{
        for(byte b : header){
            if(input.read() != b){
                throw new IOException("Not a schematic file (missing header).");
            }
        }

        int ver;
        //version, currently discarded
        if((ver = input.read()) != version){
            throw new IOException("Unknown version: " + ver);
        }

        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(input))){

            short width = stream.readShort(), height = stream.readShort();
            IntMap<Block> blocks = new IntMap<>();
            byte length = stream.readByte();
            for(int i = 0; i < length; i++){
                Block block = Vars.content.getByName(ContentType.block, stream.readUTF());
                blocks.put(i, block == null ? Blocks.air : block);
            }

            int total = stream.readInt();
            Array<Stile> tiles = new Array<>(total);
            for(int i = 0; i < total; i++){
                Block block = blocks.get(stream.readByte());
                int position = stream.readInt();
                int config = stream.readInt();
                byte rotation = stream.readByte();
                if(block != Blocks.air){
                    tiles.add(new Stile(block, Pos.x(position), Pos.y(rotation), config, rotation));
                }
            }

            return new Schematic(tiles, width, height);
        }
    }

    public static void write(Schematic schematic, FileHandle file) throws IOException{
        write(schematic, file.write(false, 1024));
    }

    public static void write(Schematic schematic, OutputStream output) throws IOException{
        output.write(header);
        output.write(version);

        try(DataOutputStream stream = new DataOutputStream(new DeflaterOutputStream(output))){

            stream.writeShort(schematic.width);
            stream.writeShort(schematic.height);
            OrderedSet<Block> blocks = new OrderedSet<>();
            schematic.tiles.each(t -> blocks.add(t.block));

            //create dictionary
            stream.writeByte(blocks.size);
            for(int i = 0; i < blocks.size; i++){
                stream.writeUTF(blocks.orderedItems().get(i).name);
            }

            stream.writeInt(schematic.tiles.size);
            //write each tile
            for(Stile tile : schematic.tiles){
                stream.writeByte(blocks.orderedItems().indexOf(tile.block));
                stream.writeInt(Pos.get(tile.x, tile.y));
                stream.writeInt(tile.config);
                stream.writeByte(tile.rotation);
            }
        }
    }

    //endregion
}
