package net.wasdev.gameon.pictureroom;

import java.io.IOException;
import java.io.InputStream;

public class PictureRoomPicture {
    private final String name;
    private final String description;
    
    private PictureRoomPicture(String name, String desc) {
        this.name = name;
        description = desc;
    }
    
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public static PictureRoomPicture getInstance(String name, String imgfile) {
        try(InputStream stream = PictureRoomPicture.class.getResourceAsStream("/" + imgfile +".txt")) {
            if(stream == null) {
                return new PictureRoomPicture(name, "Oops, no picture description could be found.");
            }
            StringBuffer desc = new StringBuffer();
            int read = 0;
            byte[] buffer = new byte[1024];
            while((read = stream.read(buffer)) != -1) {
                desc.append(new String(buffer, 0, read));
            } 
            return new PictureRoomPicture(name, desc.toString());
        } catch (IOException e) {
            System.out.println("Error reading room description : " + e.getMessage());
            return new PictureRoomPicture(name, "Oops, no picture description could be found.");
        }
    }
}
