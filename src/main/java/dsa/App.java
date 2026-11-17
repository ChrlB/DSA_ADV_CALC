package dsa;
import javax.swing.*;
import components.*;
/**
 * Hello world!
 *
 */
public class App {
    public static void main( String[] args )    {
        try {
            var window = new JFrame();

            // window.setIconImage(icon.getImage());
            window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            window.setLayout(null);
            window.setSize(815,520);
            window.setResizable(false);
            window.setTitle("LOGIN");
            window.setVisible(true);
            //window.getContentPane().setBackground(new Color(0x10232A));
            //window.setLocationRelativeTo(null);
        } catch (Exception e) {
            System.out.println(e);
        }
        
    }
}
