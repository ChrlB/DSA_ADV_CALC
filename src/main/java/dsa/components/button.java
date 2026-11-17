package dsa.components;

import javax.swing.JButton;


public class button extends JButton{

  public button(String text, int x_position, int y_position) {
      super(text); // Sets the button's label text natively
      this.setBounds(x_position, y_position, 100, 40); // Sets position and size (width, height)
  }
}
