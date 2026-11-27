package rotp.ui;

import java.awt.BasicStroke;
import java.awt.Stroke;

public interface ScaledInteger {
    private static int scale(int value) { return rotp.Launcher.headless() ? value : RotPUI.scaledSize(value); }
	int s1	= scale(1);
	int s2	= scale(2);
	int s3	= scale(3);
	int s4	= scale(4);
	int s5	= scale(5);
	int s6	= scale(6);
	int s7	= scale(7);
	int s8	= scale(8);
	int s9	= scale(9);
	int s10	= scale(10);
	int s11	= scale(11);
	int s12	= scale(12);
	int s13	= scale(13);
	int s14	= scale(14);
	int s15	= scale(15);
	int s16	= scale(16);
	int s17	= scale(17);
	int s18	= scale(18);
	int s19	= scale(19);
	int s20	= scale(20);
	int s21	= scale(21);
	int s22	= scale(22);
	int s23	= scale(23);
	int s24	= scale(24);
	int s25	= scale(25);
	int s26	= scale(26);
	int s27	= scale(27);
	int s28	= scale(28);
	int s29	= scale(29);
	int s30	= scale(30);
	int s31	= scale(31);
	int s32	= scale(32);
	int s33	= scale(33);
	int s34	= scale(34);
	int s35	= scale(35);
	int s36	= scale(36);
	int s37	= scale(37);
	int s38	= scale(38);
	int s39	= scale(39);
	int s40	= scale(40);
	int s41	= scale(41);
	int s42	= scale(42);
	int s43	= scale(43);
	int s44	= scale(44);
	int s45	= scale(45);
	int s46	= scale(46);
	int s47	= scale(47);
	int s48	= scale(48);
	int s49	= scale(49);
	int s50	= scale(50);
	int s51	= scale(51);
	int s52	= scale(52);
	int s53	= scale(53);
	int s54	= scale(54);
	int s55	= scale(55);
	int s56	= scale(56);
	int s57	= scale(57);
	int s58	= scale(58);
	int s59	= scale(59);
	int s60	= scale(60);
	int s61	= scale(61);
	int s62	= scale(62);
	int s63	= scale(63);
	int s64	= scale(64);
	int s65	= scale(65);
	int s66	= scale(66);
	int s67	= scale(67);
	int s68	= scale(68);
	int s69	= scale(69);
	int s70	= scale(70);
	int s71	= scale(71);
	int s72	= scale(72);
	int s73	= scale(73);
	int s74	= scale(74);
	int s75	= scale(75);
	int s76	= scale(76);
	int s77	= scale(77);
	int s78	= scale(78);
	int s79	= scale(79);
	int s80	= scale(80);
	int s81	= scale(81);
	int s82	= scale(82);
	int s83	= scale(83);
	int s84	= scale(84);
	int s85	= scale(85);
	int s86	= scale(86);
	int s87	= scale(87);
	int s88	= scale(88);
	int s89	= scale(89);
	int s90	= scale(90);
	int s91	= scale(91);
	int s92	= scale(92);
	int s93	= scale(93);
	int s94	= scale(94);
	int s95	= scale(95);
	int s96	= scale(96);
	int s97	= scale(97);
	int s98	= scale(98);
	int s99	= scale(99);
	int s100 = scale(100);
	int s110 = scale(110);
	int s120 = scale(120);
	int s130 = scale(130);
	int s140 = scale(140);
	int s150 = scale(150);
	int s160 = scale(160);
	int s170 = scale(170);
	int s180 = scale(180);
	int s190 = scale(190);
	int s200 = scale(200);
	int s250 = scale(250);
	int s300 = scale(300);
	int s400 = scale(400);
	int s500 = scale(500);
	int s600 = scale(600);
	int s700 = scale(700);

	Stroke stroke1 = new BasicStroke(s1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: round cap and join
	Stroke stroke2 = new BasicStroke(s2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: round cap and join
	Stroke stroke3 = new BasicStroke(s3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: round cap and join
	Stroke stroke4 = new BasicStroke(s4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: round cap and join
	Stroke stroke5 = new BasicStroke(s5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: round cap and join
	Stroke stroke6 = new BasicStroke(s6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: round cap and join
	Stroke stroke7 = new BasicStroke(s7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: round cap and join
	Stroke stroke8 = new BasicStroke(s8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: add strokes
	Stroke stroke9 = new BasicStroke(s9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: add strokes
	Stroke stroke10 = new BasicStroke(s10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); // modnar: add strokes
	default Stroke baseStroke(int n) {
		switch(n) {
			case 1: return stroke1;
			case 2: return stroke2;
			case 3: return stroke3;
			case 4: return stroke4;
			case 5: return stroke5;
			case 6: return stroke6;
			case 7: return stroke7;
			case 8: return stroke8; // modnar: add strokes
			case 9: return stroke9; // modnar: add strokes
			case 10: return stroke10; // modnar: add strokes
			default: return stroke1;
			}
		}
	int cnr = s5; // corner radius
}
