/***************************************************
*	Module Name		:	color_bar		   
*	Engineer			:	小梅哥
*	Target Device	:	10CL025YU256C8
*	Tool versions	:	Quartus Prime 18.1
*	Create Date		:	2021-12-20
*	Revision			:	v1.0
*	Description		:  彩条实验的彩条颜色和区域定义
**************************************************/
module color_bar(
	input [11:0] hcount,
	input [11:0] vcount,
	output reg [23:0] disp_data
);
	
	//定义颜色编码
	localparam 
		BLACK	= 24'h000000, //黑色
		BLUE	= 24'h0000FF, //蓝色
		RED		= 24'hFF0000, //红色
		PURPPLE	= 24'hFF00FF, //紫色
		GREEN	= 24'h00FF00, //绿色
		CYAN	= 24'h00FFFF, //青色
		YELLOW	= 24'hFFFF00, //黄色
		WHITE	= 24'hFFFFFF; //白色

//	localparam 
//		BLACK	= 16'h0000, //黑色
//		BLUE	= 16'h001F, //蓝色
//		RED		= 16'hF800, //红色
//		PURPPLE	= 16'hF81F, //紫色
//		GREEN	= 16'h07E0, //绿色
//		CYAN	= 16'h07FF, //青色
//		YELLOW	= 16'hFFE0, //黄色
//		WHITE	= 16'hFFFF; //白色
	
	//定义每个像素块的默认显示颜色值
	localparam 
		R0_C0 = BLACK,	//第0行0列像素块
		R0_C1 = BLUE,	//第0行1列像素块
		R1_C0 = RED,	//第1行0列像素块
		R1_C1 = PURPPLE,//第1行1列像素块
		R2_C0 = GREEN,	//第2行0列像素块
		R2_C1 = CYAN,	//第2行1列像素块
		R3_C0 = YELLOW,	//第3行0列像素块
		R3_C1 = WHITE;	//第3行1列像素块

	wire R0_act = vcount >= 0 && vcount < 180;	//正在扫描第0行
	wire R1_act = vcount >= 180 && vcount < 360;//正在扫描第1行
	wire R2_act = vcount >= 360 && vcount < 540;//正在扫描第2行
	wire R3_act = vcount >= 540 && vcount < 720;//正在扫描第3行
	
	wire C0_act = hcount >= 0 && hcount < 640; //正在扫描第0列
	wire C1_act = hcount >= 640 && hcount <1280;//正在扫描第1列 
	
	wire R0_C0_act = R0_act & C0_act;	//第0行0列像素块处于被扫描中标志信号
	wire R0_C1_act = R0_act & C1_act;	//第0行1列像素块处于被扫描中标志信号
	wire R1_C0_act = R1_act & C0_act;	//第1行0列像素块处于被扫描中标志信号
	wire R1_C1_act = R1_act & C1_act;	//第1行1列像素块处于被扫描中标志信号
	wire R2_C0_act = R2_act & C0_act;	//第2行0列像素块处于被扫描中标志信号
	wire R2_C1_act = R2_act & C1_act;	//第2行1列像素块处于被扫描中标志信号
	wire R3_C0_act = R3_act & C0_act;	//第3行0列像素块处于被扫描中标志信号
	wire R3_C1_act = R3_act & C1_act;	//第3行1列像素块处于被扫描中标志信号
	
	always @ (*)
		case({R3_C1_act,R3_C0_act,R2_C1_act,R2_C0_act,
				R1_C1_act,R1_C0_act,R0_C1_act,R0_C0_act})
			8'b0000_0001:disp_data = R0_C0;
			8'b0000_0010:disp_data = R0_C1;
			8'b0000_0100:disp_data = R1_C0;
			8'b0000_1000:disp_data = R1_C1;
			8'b0001_0000:disp_data = R2_C0;
			8'b0010_0000:disp_data = R2_C1;
			8'b0100_0000:disp_data = R3_C0;
			8'b1000_0000:disp_data = R3_C1;
			default:disp_data = R0_C0;
		endcase
	
endmodule
