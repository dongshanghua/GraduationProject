package ServerSide;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import javax.imageio.ImageIO;

import org.w3c.dom.css.ElementCSSInlineStyle;

import CommonPackage.Config;
import CommonPackage.VideoInfo;

public class Interaction {
	
	/*
	 * 发送分类列表
	 * */
	
	public void sendCategoryList(int mode,
			ObjectOutputStream objectOutputStream) {
		//这个功能使用DatebaseOperation类
		DatebaseQuery datebaseQuery = null;//数据库查询类
		datebaseQuery = new DatebaseQuery();
		HashMap<String, String> categoryMap = datebaseQuery.getCategory(mode);
		///打开序列化输出流
		try {
			objectOutputStream.writeObject(categoryMap);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}

	/*
	 * 发送视频列表
	 * */
	public void sendVideoList(int mode,String category,
			int videoDisplayStart,int videoDisplayStep,
			ObjectOutputStream objectOutputStream) {
		/* 这个功能：
		 * 使用DatebaseOperation类获取指定数量的视频
		 * */
		DatebaseQuery datebaseQuery = null;//数据库查询类
		/*查询数据库*/
		datebaseQuery = new DatebaseQuery();
		ArrayList<VideoInfo> videoInfoList=datebaseQuery.getVideoSet(mode, 
				category, videoDisplayStart, videoDisplayStep);
		/*
		 * 序列化对象不能用读取到null这样的方法来判断读取完毕，
		 * 所以先告诉客户端有几个对象。不同类型的流不可混用，在此全部用对象流*/
		//打开序列化输出流
		//告诉客户端，有几条视频信息
		try {
			objectOutputStream.writeObject(new Integer(videoInfoList.size()));
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		/*
		 * 从数据库读取到的videoInfo集合，每个对象的bufferedImage字段没有被填充*
		 * 现在开始填充，填充完一个就发给客户端一个
		 */
		Iterator<VideoInfo> iterator = videoInfoList.iterator();
		//对每个视频分别取截图并设置到视频对象里，然后写回客户端
		try{
			while(iterator.hasNext()){
				VideoInfo videoInfo = iterator.next();
				
				/*获得缩略图路径，以便读取缩略图*/
				String thumbnailPartialPath = "thumbnail/";//缩略图路径
				String fileID = videoInfo.getFileID();
				//默认绝对路径前缀
				String pathPrefix = Config.getValue("pathPrefix", "/home/mirage/rtsp-relay/file/");
				//拼接绝对路径
				String thumbnailPath = pathPrefix+thumbnailPartialPath+fileID+".jpg";
				/*读取缩略图*/
				BufferedImage bufferedImage = 
						ImageIO.read(new FileInputStream(thumbnailPath));
				/*缩略图设入videoInfo*/
				videoInfo.setBufferedImage(bufferedImage);//将图片对象设入videoInfo对象
				/*将填充好的videoInfo发给客户端*/
				objectOutputStream.writeObject(videoInfo);//序列化发给客户端
			}
		}catch(Exception e){
			e.printStackTrace();
		}
	}

	/*
	 * 发送视频流
	 * */
	public void streamVideo(String fileRelativePath,
			BufferedReader readFromClient, PrintWriter printToClient) {
		InputStream inputFromShell = null;//读取shell
		Process pc = null;
		ProcessBuilder pb = null;
		try {
			
			int streamName = StreamName.getStreamName();
			//告诉客户端流名称，本次发送不需要心跳应答
			printToClient.println(streamName);
			//文件的默认绝对路径前缀
			String pathPrefix = Config.getValue("pathPrefix", "/home/mirage/rtsp-relay/file/");
			//拼接绝对路径
			String fileAbsolutePath  = pathPrefix + fileRelativePath;
			
			ArrayList<String> command = new ArrayList<>();//命令数组
			command.add("ffmpeg");
			command.add("-re");
			command.add("-i");
			command.add(fileAbsolutePath);
			command.add("-c");
			command.add("copy");
			command.add("-f");
			command.add("rtsp");
			command.add("rtsp://"+"127.0.0.1"+"/live/"+streamName);
			pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			pc = pb.start();
			inputFromShell = pc.getInputStream();
			BufferedReader readFromShell = new BufferedReader(new InputStreamReader(inputFromShell));
			String tmp_in = null;
			// 如果接收方挂了，底层socket不会关闭，所以发送方不会出现异常。但是客户端立即重启的话就会
			// 得不到端口而报异常，除非设置端口重用选项。实际测试中并未出现问题，
			// 所以客户端暂时不用设置SO_REUSEADDR。
			try {
				while ((tmp_in = readFromShell.readLine()) != null) {
					System.out.println(tmp_in);
					if( ! tmp_in.toLowerCase().startsWith("frame="))//还没开始正式发送
						printToClient.println(DefineConstant.WAIT);
					else{//开始发送视频了
						printToClient.println(DefineConstant.OK);
						break;
					}
				}
				
				/*
				 * 如果FFmpeg播放出错，则此时它已经死了，
				 * 如果没死就是一切正常，可以进入心跳包应答模式
				 * */
				if(pc.isAlive())
					do {
						Thread.sleep(1000);
						printToClient.println("Probe");//探测客户端是否死亡
					} while ((readFromClient.readLine()) != null);//客户端死了就没必要继续了
				else 
					System.out.println("!!!");
				
			} catch (Exception e) {
				e.printStackTrace();
			}
			pc.destroy();
			StreamName.releaseStreamName(streamName);//释放数据流名字
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {// 上级函数负责printToClient的关闭，本函数只负责关掉自己的命令行读入流即可
				if (inputFromShell != null)
					inputFromShell.close();
				System.out.println("Shell ffmpeg has stopped");
			} catch (IOException e) {
				e.printStackTrace();
			}
		} // finally
	}
}
